package com.example.midun.data

import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.data.real.ChatSnapshot
import com.example.midun.data.real.ChatStore
import com.example.midun.data.real.RealFileSystem
import com.example.midun.data.real.RealUsbManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天仓库（联系人 + 消息，内存态 + 真卡持久化）。
 *
 * **持久化（M11.5.5）**：内存态空启动；真卡认证成功即经 [ChatStore] 从卡加载历史、每次变更写穿到卡；
 * 锁定/拔卡清内存明文。依赖选型见 [ChatStore]。
 */
@Singleton
class ChatRepository @Inject constructor(
    private val store: ChatStore,
    private val realFileSystem: RealFileSystem,
    realUsbManager: RealUsbManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 真卡认证成功（盘已打开）→ 从卡加载聊天；锁定/拔卡（离开 AUTHENTICATED）→ 清内存明文。
        // wasAuthed 守卫：初始 false 的首个发射不触发清理，仅真正离开认证态才清（M11.6.3 安全加固）。
        scope.launch {
            var wasAuthed = false
            realUsbManager.deviceStatus
                .map { it.status == UsbDeviceStatus.AUTHENTICATED }
                .distinctUntilChanged()
                .collect { authed ->
                    if (authed) {
                        store.load()?.let { applySnapshot(it) }
                        sweepExpiredCache() // 7 天 TTL：清掉过期的文件预览缓存（认证后扫一遍，见 file-transfer 阶段3）
                        wasAuthed = true
                    } else if (wasAuthed) {
                        clearInMemory() // 已写穿到卡，重认证后重载
                        wasAuthed = false
                    }
                }
        }
    }

    /**
     * 文件预览缓存 7 天 TTL（file-transfer 阶段3）：按消息 [ChatMessage.timestamp] 删超期的卡内暂存
     * （`.recv_<id>` 接收暂存 + `.sent_<id>` 发送方副本）。安全 App 无常驻定时器 → 每次认证后扫一遍。
     * **只删缓存前缀文件，绝不碰隐私文件夹**（已保存的永久副本不在此列，过期后仍可从文件夹预览）。
     * streamDelete 对不存在的路径是 no-op，故文件夹来源发送（无 `.sent_` 文件）不受影响。
     */
    private fun sweepExpiredCache() {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        messages.values.flatten().forEach { m ->
            if (m.type == MessageType.FILE && m.timestamp < cutoff) {
                realFileSystem.streamDelete(FileCachePaths.recv(m.id))
                realFileSystem.streamDelete(FileCachePaths.sent(m.id))
            }
        }
    }

    /** 所有 FILE 消息推出的候选缓存路径（.recv_ + .sent_；是否存在由调用方判断）。 */
    private fun cachePaths(): List<String> =
        messages.values.flatten()
            .filter { it.type == MessageType.FILE }
            .flatMap { listOf(FileCachePaths.recv(it.id), FileCachePaths.sent(it.id)) }

    /** 现存文件预览缓存统计（设置页展示）：返回 (文件数, 总字节)。仅认证态有缓存，未认证恒 (0,0)。 */
    suspend fun cacheStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var count = 0
        var bytes = 0L
        cachePaths().forEach { p -> realFileSystem.fileSizeOrNull(p)?.let { count++; bytes += it } }
        count to bytes
    }

    /**
     * 手动清除全部文件预览缓存（设置页「清除缓存」）：只删 `.recv_`/`.sent_` 暂存，
     * **不碰隐私文件夹/聊天记录**（已保存到文件夹的永久副本不受影响）。返回释放的字节数。
     */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        cachePaths().forEach { p ->
            val sz = realFileSystem.fileSizeOrNull(p) ?: return@forEach
            if (realFileSystem.streamDelete(p)) freed += sz
        }
        freed
    }

    // 内存态联系人/消息：空启动，真卡认证后由 [ChatStore] 从卡加载替换（无种子数据）。
    private val contacts = mutableListOf<Contact>()
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()

    fun getContacts(): List<Contact> = contacts.sortedWith(
        compareByDescending<Contact> { it.isPinned }.thenByDescending { it.lastMessageTime }
    )

    fun getMessages(contactId: String): List<ChatMessage> =
        messages[contactId]?.toList() ?: emptyList()

    suspend fun sendMessage(
        contactId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        messageId: String? = null,
        status: MessageStatus = MessageStatus.SENT,
        burnAfterRead: Boolean = false,
        burnTtl: Int = 0
    ): Result<ChatMessage> {
        delay(200)
        val msg = ChatMessage(
            // 联网发送传入稳定 id（两端同 id，供撤回引用）；本地/离线则自生成。
            id = messageId ?: "msg_${System.currentTimeMillis()}",
            contactId = contactId,
            content = content,
            type = type,
            isMine = true,
            // 离线/发送失败标 FAILED（UI 显「未送达」）；联网成功为 SENT。
            status = status,
            // 阅后即焚模式下发出的消息（B 阶段）：本端气泡也走焚毁样式。
            burnAfterRead = burnAfterRead,
            burnTtl = burnTtl
        )
        messages.getOrPut(contactId) { mutableListOf() }.add(msg)
        updateContactPreview(contactId)
        persist()
        return Result.success(msg)
    }

    suspend fun deleteMessage(messageId: String, contactId: String): Result<Unit> {
        delay(100)
        messages[contactId]?.removeAll { it.id == messageId }
        updateContactPreview(contactId)
        persist()
        return Result.success(Unit)
    }

    /**
     * 撤回消息（M10.5）：保留占位但**抹掉原文**（content/文件名/大小清空）并标记 recalled，
     * 渲染为「已撤回」墓碑。安全考量：撤回须让内容从存储消失，不只是 UI 隐藏。
     */
    fun markRecalled(messageId: String, contactId: String) {
        val list = messages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            content = "",
            type = MessageType.TEXT,
            fileName = null,
            fileSize = null,
            recalled = true
        )
        updateContactPreview(contactId)
        persist()
    }

    /**
     * 插入一条居中系统行（阅后即焚开/关提示，B 阶段）。type=SYSTEM，content 即提示文案；
     * 非「我方/对方」消息、不计未读。两端各自插入（本端开关时本地插，对端经 BURN_MODE 帧插）。
     */
    fun addSystemMessage(contactId: String, content: String) {
        val msg = ChatMessage(
            id = "sys_${System.currentTimeMillis()}_${(0..9999).random()}",
            contactId = contactId,
            content = content,
            type = MessageType.SYSTEM,
            isMine = false,
            status = MessageStatus.RECEIVED
        )
        messages.getOrPut(contactId) { mutableListOf() }.add(msg)
        updateContactPreview(contactId)
        persist()
    }

    /**
     * 未建立会话时发送消息后，确保**当前断连段末尾**有一条「去建立连接」系统提示行（带可点链接）。
     * 从末尾回扫：遇到「已送达/已收到」的真实消息即停（保留更早的历史提示，重连后不删）；途中遇到本段
     * 已有的提示则先移除——再在末尾新插一条。效果 = 每个断连段始终只有一条、且永远在最新消息之下。
     */
    fun addConnectPromptIfNeeded(contactId: String) {
        val list = messages.getOrPut(contactId) { mutableListOf() }
        val iter = list.listIterator(list.size)
        while (iter.hasPrevious()) {
            val m = iter.previous()
            val delivered = (m.isMine && m.status == MessageStatus.SENT) ||
                (!m.isMine && m.type != MessageType.SYSTEM && m.status == MessageStatus.RECEIVED)
            if (delivered) break // 上次还连着 → 早于此的提示属历史段，保留
            if (m.type == MessageType.SYSTEM && m.connectPrompt) iter.remove() // 移除本段旧提示，稍后挪到末尾
        }
        list.add(
            ChatMessage(
                id = "sys_${System.currentTimeMillis()}_${(0..9999).random()}",
                contactId = contactId,
                content = "当前未建立会话，消息无法送达",
                type = MessageType.SYSTEM,
                isMine = false,
                status = MessageStatus.RECEIVED,
                connectPrompt = true
            )
        )
        updateContactPreview(contactId)
        persist()
    }

    /**
     * 焚毁阅后即焚消息（B 阶段）：保留占位但**抹掉原文**并标记 burned，渲染为焚毁墓碑。
     * 与 markRecalled 同为「原地把真实消息变残骸」——保 id 与时间位置，供 BURN 帧按 id 双端引用。
     */
    fun markBurned(messageId: String, contactId: String) {
        val list = messages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            content = "",
            type = MessageType.TEXT,
            fileName = null,
            fileSize = null,
            burnAfterRead = false,
            burnTtl = 0,
            burned = true
        )
        updateContactPreview(contactId)
        persist()
    }

    suspend fun clearMessages(contactId: String): Result<Unit> {
        delay(300)
        messages[contactId]?.clear()
        updateContactPreview(contactId)
        persist()
        return Result.success(Unit)
    }

    suspend fun deleteContact(contactId: String): Result<Unit> {
        delay(500)
        messages.remove(contactId)
        contacts.removeAll { it.id == contactId }
        persist()
        return Result.success(Unit)
    }

    /** 扫码建联：新增一个联系人。由 P2PSessionManager 建联系人时调用。 */
    fun addContact(contact: Contact) {
        contacts.add(contact)
        persist()
    }

    /**
     * 无卡测试模式种子联系人「测试1」（[[project_midun_nocard_testmode]]，T1）：进入测试模式时种一个占位联系人，
     * 让主页/通信 Tab 不空。它本身无活动会话 → 直接发消息会显「未送达」；真正收发靠两台无卡机互扫自建联系人。
     * 幂等：已存在同 deviceId 则不重复种。仅内存（未认证 persist 为 no-op，不写卡）。
     */
    fun seedTestContact() {
        if (contacts.any { it.deviceId == TEST_CONTACT_DEVICE_ID }) return
        contacts.add(
            Contact(
                id = "c_test_seed",
                deviceId = TEST_CONTACT_DEVICE_ID,
                remark = "测试1",
                lastMessageTime = System.currentTimeMillis()
            )
        )
    }

    /** 修改联系人备注（用户在联系人资料页编辑）。由 ChatViewModel.updateRemark 调用。 */
    fun updateRemark(contactId: String, remark: String) {
        contacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> contacts[idx] = contacts[idx].copy(remark = remark) }
        persist()
    }

    /** 是否已存在该 deviceId 的联系人（P2PSessionManager 身份交换去重用）。 */
    fun findContactByDevice(deviceId: String): Contact? =
        contacts.firstOrNull { it.deviceId == deviceId }

    /**
     * 收到对端消息入库（isMine=false）+ 未读 +1 + 刷新预览。由 P2PSessionManager 接收循环调用（M10.4）。
     */
    fun receiveMessage(
        contactId: String,
        content: String,
        type: MessageType,
        messageId: String? = null,
        burnAfterRead: Boolean = false,
        burnTtl: Int = 0
    ): ChatMessage {
        val msg = ChatMessage(
            // 用发送方的稳定 id（供撤回引用）；缺省才自生成。
            id = messageId ?: "msg_${System.currentTimeMillis()}_${(0..9999).random()}",
            contactId = contactId,
            content = content,
            type = type,
            isMine = false,
            status = MessageStatus.RECEIVED,
            // 焚毁消息（B 阶段）：接收端先遮罩，点开后按 burnTtl 倒计时焚毁。
            burnAfterRead = burnAfterRead,
            burnTtl = burnTtl
        )
        messages.getOrPut(contactId) { mutableListOf() }.add(msg)
        val idx = contacts.indexOfFirst { it.id == contactId }
        if (idx >= 0) {
            contacts[idx] = contacts[idx].copy(unreadCount = contacts[idx].unreadCount + 1)
        }
        updateContactPreview(contactId)
        persist()
        return msg
    }

    // —— 文件消息（M11.5.3 file-transfer）——

    /**
     * 插入一条文件消息（发送端 [isMine]=true 起始 SENDING；接收端 false 起始 RECEIVED、[savedFolderId]=null 待保存）。
     * 进度是瞬时态、不入库（由 `P2PSessionManager.transferProgress` 单独驱动），这里只管消息本体与状态。
     */
    fun addFileMessage(
        contactId: String,
        messageId: String,
        isMine: Boolean,
        fileName: String,
        fileSize: Long,
        status: MessageStatus,
        savedFolderId: String? = null,
        localPath: String? = null,
        type: MessageType = MessageType.FILE,
        audioDurationSec: Int = 0,
        burnAfterRead: Boolean = false,
        burnTtl: Int = 0
    ): ChatMessage {
        val msg = ChatMessage(
            id = messageId,
            contactId = contactId,
            content = fileName,
            type = type,
            isMine = isMine,
            status = status,
            fileName = fileName,
            fileSize = fileSize,
            savedFolderId = savedFolderId,
            localPath = localPath,
            audioDurationSec = audioDurationSec,
            burnAfterRead = burnAfterRead,
            burnTtl = burnTtl
        )
        messages.getOrPut(contactId) { mutableListOf() }.add(msg)
        if (!isMine) {
            val idx = contacts.indexOfFirst { it.id == contactId }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(unreadCount = contacts[idx].unreadCount + 1)
        }
        updateContactPreview(contactId)
        persist()
        return msg
    }

    /**
     * 记下发送方自己的卡内预览副本路径（file-transfer 阶段2）：手机来源发送成功后留的 `0:/.sent_<id>`。
     * 隐私文件夹来源在 addFileMessage 时即带 localPath，无需此设。按 id 原地改。
     */
    fun setFileLocalPath(messageId: String, contactId: String, localPath: String) {
        val list = messages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(localPath = localPath)
        persist()
    }

    /** 更新文件消息状态（发送 SENDING→SENT/FAILED；接收完成/失败）。按 id 原地改。 */
    fun updateFileStatus(messageId: String, contactId: String, status: MessageStatus) {
        val list = messages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(status = status)
        persist()
    }

    /**
     * 接收文件保存到隐私文件夹后，记下落地文件夹路径（savedFolderId 非 null = 已保存、气泡可预览）。
     * [finalFileName] 为实际落地文件名（冲突避让后可能加了序号）→ 同步更新 fileName/content，保证预览路径正确。
     */
    fun setFileSaved(messageId: String, contactId: String, savedFolderId: String, finalFileName: String) {
        val list = messages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(savedFolderId = savedFolderId, fileName = finalFileName, content = finalFileName)
        persist()
    }

    /** 切换联系人置顶状态。由 ChatViewModel.togglePin 调用；置顶项在 getContacts 中排在最前。 */
    fun togglePin(contactId: String) {
        contacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> contacts[idx] = contacts[idx].copy(isPinned = !contacts[idx].isPinned) }
        persist()
    }

    /** 进入会话时清除该联系人的未读计数。由 ChatViewModel.markRead 调用。 */
    fun markContactRead(contactId: String) {
        contacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> contacts[idx] = contacts[idx].copy(unreadCount = 0) }
        persist()
    }

    /** 整卡擦除时调用：清空所有联系人与消息。由 SecurityCardManager.wipeAll()/wipeUserData() 统一触发。 */
    fun clear() {
        contacts.clear()
        messages.clear()
        persist()
    }

    /** 仅清内存（锁定/拔卡时；卡内数据已写穿，不再持久化，重认证后重载）。 */
    private fun clearInMemory() {
        contacts.clear()
        messages.clear()
    }

    /** 用卡内快照替换内存（真卡认证后加载）。 */
    private fun applySnapshot(s: ChatSnapshot) {
        contacts.clear()
        contacts.addAll(s.contacts)
        messages.clear()
        s.messages.forEach { (cid, list) -> messages[cid] = list.toMutableList() }
    }

    /** 写穿到隐藏区（store 内部判活动态：未认证 no-op、认证态整表覆盖写）。 */
    private fun persist() {
        scope.launch { store.save(ChatSnapshot(contacts.toList(), messages.mapValues { it.value.toList() })) }
    }

    private fun updateContactPreview(contactId: String) {
        val index = contacts.indexOfFirst { it.id == contactId }
        if (index == -1) return

        // 预览取最后一条**非系统**消息（系统行如「去建立连接」/焚毁开关不应作为会话列表预览）。
        val lastMessage = messages[contactId]
            ?.filter { it.type != MessageType.SYSTEM }
            ?.maxByOrNull { it.timestamp }

        contacts[index] = contacts[index].copy(
            lastMessage = when {
                lastMessage == null -> ""
                lastMessage.recalled -> "[消息已撤回]"
                lastMessage.burned -> "🔥 [已焚毁]"
                lastMessage.burnAfterRead -> "🔥 [阅后即焚]" // 焚毁消息预览不泄漏原文
                lastMessage.type == MessageType.AUDIO -> "[语音]"
                lastMessage.type == MessageType.FILE -> "[文件] ${lastMessage.fileName ?: ""}"
                else -> lastMessage.content
            },
            lastMessageTime = lastMessage?.timestamp ?: 0L
        )
    }

    private companion object {
        /** 文件预览缓存存活时长：7 天（用户定，file-transfer 阶段3）。 */
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** 无卡测试模式种子联系人「测试1」的设备标识（去重用）。 */
        const val TEST_CONTACT_DEVICE_ID = "TEST-CONTACT-1"
    }
}
