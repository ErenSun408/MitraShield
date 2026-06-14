package com.example.midun.data.mock

import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.data.real.ChatSnapshot
import com.example.midun.data.real.ChatStore
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

/**
 * 聊天仓库（联系人 + 消息，内存版 + 真卡持久化）。
 *
 * **持久化（M11.5.5）**：经 [ChatStore] 落安全卡隐藏区。模拟模式下 store 非活动 → 保留内存种子数据
 * 开发流；真卡模式下认证成功即从卡加载历史（替换内存种子）、每次变更写穿到卡。模式判据/依赖选型见 [ChatStore]。
 */
@Singleton
class MockChatRepository @Inject constructor(
    private val store: ChatStore,
    realUsbManager: RealUsbManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 真卡认证成功（盘已打开）→ 从卡加载聊天，替换内存种子；锁定/拔卡（离开 AUTHENTICATED）→ 清内存明文。
        // 模拟模式 Real 永不认证 → wasAuthed 恒 false，初始 false 不误清种子（M11.6.3 安全加固）。
        scope.launch {
            var wasAuthed = false
            realUsbManager.deviceStatus
                .map { it.status == UsbDeviceStatus.AUTHENTICATED }
                .distinctUntilChanged()
                .collect { authed ->
                    if (authed) {
                        store.load()?.let { applySnapshot(it) }
                        wasAuthed = true
                    } else if (wasAuthed) {
                        clearInMemory() // 已写穿到卡，重认证后重载
                        wasAuthed = false
                    }
                }
        }
    }

    private val mockContacts = mutableListOf(
        Contact("c1", "DEVICE_ABC123", "张三", "好的，文件已收到", System.currentTimeMillis() - 3600000, isOnline = true),
        Contact("c2", "DEVICE_DEF456", "李四（工作）", "明天再说", System.currentTimeMillis() - 86400000, isOnline = false, unreadCount = 3),
        Contact("c3", "DEVICE_GHI789", "王五", "收到", System.currentTimeMillis() - 172800000, isOnline = false),
    )

    private val mockMessages = mutableMapOf<String, MutableList<ChatMessage>>(
        "c1" to mutableListOf(
            ChatMessage("m1", "c1", "你好，我是张三", isMine = false, timestamp = System.currentTimeMillis() - 7200000),
            ChatMessage("m2", "c1", "你好！", isMine = true, timestamp = System.currentTimeMillis() - 7100000),
            ChatMessage("m3", "c1", "文件发给你了", isMine = false, timestamp = System.currentTimeMillis() - 3700000, type = MessageType.FILE, fileName = "合同.pdf", fileSize = 2048000),
            ChatMessage("m4", "c1", "好的，文件已收到", isMine = true, timestamp = System.currentTimeMillis() - 3600000),
        ),
        "c2" to mutableListOf(
            ChatMessage("m5", "c2", "这个方案怎么样", isMine = true, timestamp = System.currentTimeMillis() - 90000000),
            ChatMessage("m6", "c2", "明天再说", isMine = false, timestamp = System.currentTimeMillis() - 86400000),
        )
    )

    fun getContacts(): List<Contact> = mockContacts.sortedWith(
        compareByDescending<Contact> { it.isPinned }.thenByDescending { it.lastMessageTime }
    )

    fun getMessages(contactId: String): List<ChatMessage> =
        mockMessages[contactId]?.toList() ?: emptyList()

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
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        updateContactPreview(contactId)
        persist()
        return Result.success(msg)
    }

    suspend fun deleteMessage(messageId: String, contactId: String): Result<Unit> {
        delay(100)
        mockMessages[contactId]?.removeAll { it.id == messageId }
        updateContactPreview(contactId)
        persist()
        return Result.success(Unit)
    }

    /**
     * 撤回消息（M10.5）：保留占位但**抹掉原文**（content/文件名/大小清空）并标记 recalled，
     * 渲染为「已撤回」墓碑。安全考量：撤回须让内容从存储消失，不只是 UI 隐藏。
     */
    fun markRecalled(messageId: String, contactId: String) {
        val list = mockMessages[contactId] ?: return
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
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        updateContactPreview(contactId)
        persist()
    }

    /**
     * 焚毁阅后即焚消息（B 阶段）：保留占位但**抹掉原文**并标记 burned，渲染为焚毁墓碑。
     * 与 markRecalled 同为「原地把真实消息变残骸」——保 id 与时间位置，供 BURN 帧按 id 双端引用。
     */
    fun markBurned(messageId: String, contactId: String) {
        val list = mockMessages[contactId] ?: return
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
        mockMessages[contactId]?.clear()
        updateContactPreview(contactId)
        persist()
        return Result.success(Unit)
    }

    suspend fun deleteContact(contactId: String): Result<Unit> {
        delay(500)
        mockMessages.remove(contactId)
        mockContacts.removeAll { it.id == contactId }
        persist()
        return Result.success(Unit)
    }

    /** 扫码建联：新增一个联系人。由 P2PSessionManager 建联系人时调用。 */
    fun addContact(contact: Contact) {
        mockContacts.add(contact)
        persist()
    }

    /** 修改联系人备注（用户在联系人资料页编辑）。由 ChatViewModel.updateRemark 调用。 */
    fun updateRemark(contactId: String, remark: String) {
        mockContacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> mockContacts[idx] = mockContacts[idx].copy(remark = remark) }
        persist()
    }

    /** 是否已存在该 deviceId 的联系人（P2PSessionManager 身份交换去重用）。 */
    fun findContactByDevice(deviceId: String): Contact? =
        mockContacts.firstOrNull { it.deviceId == deviceId }

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
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        val idx = mockContacts.indexOfFirst { it.id == contactId }
        if (idx >= 0) {
            mockContacts[idx] = mockContacts[idx].copy(unreadCount = mockContacts[idx].unreadCount + 1)
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
        savedFolderId: String? = null
    ): ChatMessage {
        val msg = ChatMessage(
            id = messageId,
            contactId = contactId,
            content = fileName,
            type = MessageType.FILE,
            isMine = isMine,
            status = status,
            fileName = fileName,
            fileSize = fileSize,
            savedFolderId = savedFolderId
        )
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        if (!isMine) {
            val idx = mockContacts.indexOfFirst { it.id == contactId }
            if (idx >= 0) mockContacts[idx] = mockContacts[idx].copy(unreadCount = mockContacts[idx].unreadCount + 1)
        }
        updateContactPreview(contactId)
        persist()
        return msg
    }

    /** 更新文件消息状态（发送 SENDING→SENT/FAILED；接收完成/失败）。按 id 原地改。 */
    fun updateFileStatus(messageId: String, contactId: String, status: MessageStatus) {
        val list = mockMessages[contactId] ?: return
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
        val list = mockMessages[contactId] ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        list[idx] = list[idx].copy(savedFolderId = savedFolderId, fileName = finalFileName, content = finalFileName)
        persist()
    }

    /** 切换联系人置顶状态。由 ChatViewModel.togglePin 调用；置顶项在 getContacts 中排在最前。 */
    fun togglePin(contactId: String) {
        mockContacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> mockContacts[idx] = mockContacts[idx].copy(isPinned = !mockContacts[idx].isPinned) }
        persist()
    }

    /** 进入会话时清除该联系人的未读计数。由 ChatViewModel.markRead 调用。 */
    fun markContactRead(contactId: String) {
        mockContacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> mockContacts[idx] = mockContacts[idx].copy(unreadCount = 0) }
        persist()
    }

    /** 整卡擦除时调用：清空所有联系人与消息。由 MockUsbManager.wipeAll() 统一触发。 */
    fun clear() {
        mockContacts.clear()
        mockMessages.clear()
        persist()
    }

    /** 仅清内存（锁定/拔卡时；卡内数据已写穿，不再持久化，重认证后重载）。 */
    private fun clearInMemory() {
        mockContacts.clear()
        mockMessages.clear()
    }

    /** 用卡内快照替换内存（真卡认证后加载）。 */
    private fun applySnapshot(s: ChatSnapshot) {
        mockContacts.clear()
        mockContacts.addAll(s.contacts)
        mockMessages.clear()
        s.messages.forEach { (cid, list) -> mockMessages[cid] = list.toMutableList() }
    }

    /** 写穿到隐藏区（store 内部判活动态：模拟模式 no-op、真卡模式整表覆盖写）。 */
    private fun persist() {
        scope.launch { store.save(ChatSnapshot(mockContacts.toList(), mockMessages.mapValues { it.value.toList() })) }
    }

    private fun updateContactPreview(contactId: String) {
        val index = mockContacts.indexOfFirst { it.id == contactId }
        if (index == -1) return

        val lastMessage = mockMessages[contactId]
            ?.maxByOrNull { it.timestamp }

        mockContacts[index] = mockContacts[index].copy(
            lastMessage = when {
                lastMessage == null -> ""
                lastMessage.recalled -> "[消息已撤回]"
                lastMessage.burned -> "🔥 [已焚毁]"
                lastMessage.burnAfterRead -> "🔥 [阅后即焚]" // 焚毁消息预览不泄漏原文
                lastMessage.type == MessageType.FILE -> "[文件] ${lastMessage.fileName ?: ""}"
                else -> lastMessage.content
            },
            lastMessageTime = lastMessage?.timestamp ?: 0L
        )
    }
}
