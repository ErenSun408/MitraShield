package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.FileRepository
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.mock.MockChatRepository
import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.network.ConnectionInfo
import com.example.midun.network.P2PSessionManager
import java.io.InputStream
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: MockChatRepository,
    private val p2pManager: P2PSessionManager,
    private val fileRepository: FileRepository,
    cardManager: SecurityCardManager
) : ViewModel() {

    /** 文件传输进度（M11.5.3）：messageId → 0f..1f；转发 P2PSessionManager 单例，气泡据此画进度条。 */
    val transferProgress: StateFlow<Map<String, Float>> = p2pManager.transferProgress

    /** 真卡模式（M11.5.3 收尾）：文件传输=真卡专属，模拟模式发送按钮诚实降级提示。 */
    val realCardMode: StateFlow<Boolean> = cardManager.useRealCard

    /** 文件夹列表（接收保存 / 隐私文件夹发送来源 共用）。打开对话框时 loadFolders 刷新。 */
    private val _saveFolders = MutableStateFlow<List<FileItem>>(emptyList())
    val saveFolders: StateFlow<List<FileItem>> = _saveFolders.asStateFlow()

    /** 隐私文件夹发送来源：选中文件夹后其内文件列表（M11.5.3b）。 */
    private val _pickFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val pickFiles: StateFlow<List<FileItem>> = _pickFiles.asStateFlow()

    // 联系人列表：以 MockChatRepository（单例）为唯一数据源。
    // 因 ChatList / ChatDetail / QrCode 各自是不同 NavBackStackEntry，会拿到不同的
    // ChatViewModel 实例（同 M5.3 偏离），故每次进屏 / 改动后都 loadContacts() 重读单例，
    // 否则扫码建联、清空会话等改动在列表实例里不可见。
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    // 当前会话消息
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentContactId = MutableStateFlow<String?>(null)

    // 连接状态：转发 P2PSessionManager（单例）的真实连接状态，跨屏一致（M10.3）。
    val connectionState: StateFlow<P2PSessionManager.ConnectionState> = p2pManager.connectionState

    // 当前活动会话绑定的 contactId（M10.6）：会话详情据此判断「本会话是否已连接」以驱动加密横幅。
    val activeContactId: StateFlow<String?> =
        p2pManager.activeSession
            .map { it?.contactId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 本端阅后即焚模式（B 阶段）：转发 P2PSessionManager（单例）的真实模式状态，跨屏一致；
    // 会话详情据此高亮火苗图标、决定发出的消息是否为焚毁消息。收发行为接线在 B.2/B.3。
    val burnMode: StateFlow<P2PSessionManager.BurnMode> = p2pManager.burnMode

    // 进行中的焚毁倒计时（B 阶段）：messageId → 焚毁截止时刻；会话气泡据此显示剩余秒数。
    val burnTimers: StateFlow<Map<String, Long>> = p2pManager.burnTimers

    // 联系人搜索（patch §M6 改动1）
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredContacts: StateFlow<List<Contact>> =
        combine(_searchQuery, _contacts) { query, list ->
            if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.remark.contains(query, ignoreCase = true) ||
                        it.deviceId.contains(query, ignoreCase = true)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadContacts()
        // 收到对端消息（或身份建联系人）信号 → 重载列表；若正看该会话则一并刷消息并清未读（M10.4）。
        viewModelScope.launch {
            p2pManager.incomingMessages.collect { contactId ->
                _contacts.value = chatRepo.getContacts()
                if (_currentContactId.value == contactId) {
                    _messages.value = chatRepo.getMessages(contactId)
                    chatRepo.markContactRead(contactId)
                    _contacts.value = chatRepo.getContacts()
                }
            }
        }
    }

    /** 重读单例联系人列表。进屏（LaunchedEffect）与任何改动后调用。 */
    fun loadContacts() {
        _contacts.value = chatRepo.getContacts()
    }

    fun loadMessages(contactId: String) {
        _currentContactId.value = contactId
        _messages.value = chatRepo.getMessages(contactId)
    }

    /** 进入会话时清除该联系人的未读计数（用户 2026-05-24 要求）。清后重读 _contacts 反映角标消失。 */
    fun markRead(contactId: String) {
        chatRepo.markContactRead(contactId)
        _contacts.value = chatRepo.getContacts()
    }

    /** 切换置顶；重读 _contacts 使列表按"置顶优先"重排。 */
    fun togglePin(contactId: String) {
        chatRepo.togglePin(contactId)
        _contacts.value = chatRepo.getContacts()
    }

    fun sendMessage(content: String, type: MessageType = MessageType.TEXT) {
        val contactId = _currentContactId.value ?: return
        val session = p2pManager.activeSession.value
        viewModelScope.launch {
            if (session != null && session.contactId == contactId) {
                // 有活动会话：走 socket 加密发送（manager 内部一并本地入库）。
                // 失败也刷新（失败态标记留 M10.5）。
                p2pManager.sendText(content, type)
                reloadCurrent(contactId)
            } else {
                // 无连接：仅存本地并标 FAILED（未实时送达，且不会在对方上线后补发）。
                chatRepo.sendMessage(contactId, content, type, status = MessageStatus.FAILED)
                    .onSuccess { reloadCurrent(contactId) }
            }
        }
    }

    private fun reloadCurrent(contactId: String) {
        _messages.value = chatRepo.getMessages(contactId)
        _contacts.value = chatRepo.getContacts()
    }

    // —— 文件传输（M11.5.3 file-transfer）——

    /**
     * 发送文件（来源无关）：[openStream] 打开输入流（手机选取器 URI 流 / 隐私文件夹卡内读流）。
     * 转发 P2PSessionManager.sendFile（分块加密走文件通道 + 本地 FILE 气泡 SENDING→SENT/FAILED）。
     */
    fun sendFile(
        fileName: String, size: Long, mime: String, openStream: () -> InputStream,
        sourceCardPath: String? = null, onError: (String) -> Unit = {}
    ) {
        val contactId = _currentContactId.value ?: return
        viewModelScope.launch {
            p2pManager.sendFile(fileName, size, mime, sourceCardPath, openStream)
                .onFailure { onError("发送失败：${it.message ?: "未知错误"}") }
            reloadCurrent(contactId)
        }
    }

    /** 刷新文件夹列表（保存对话框 / 隐私文件夹发送来源共用）。 */
    fun loadSaveFolders() {
        viewModelScope.launch { _saveFolders.value = fileRepository.getFolders() }
    }

    /** 加载某隐私文件夹内的文件（发送来源选取器，M11.5.3b）。 */
    fun loadPickFiles(folderId: String) {
        viewModelScope.launch { _pickFiles.value = fileRepository.getFilesInFolder(folderId) }
    }

    /** 发送一个隐私文件夹内的文件（卡内流式读 → 加密发送，M11.5.3b）。文件已在卡上 → 源路径即发送方预览路径。 */
    fun sendCardFile(file: FileItem, onError: (String) -> Unit = {}) {
        sendFile(
            file.name, file.size, "application/octet-stream",
            openStream = { fileRepository.openFileStream(file.id) },
            sourceCardPath = file.id,
            onError = onError
        )
    }

    /** 取消在途文件发送（M11.5.3 收尾）：通知对端删半成品、本地标未送达。 */
    fun cancelFileSend() = p2pManager.cancelFileSend()

    /** 保存对话框内新建文件夹（默认不可拷贝），成功回调返回新文件夹 id 供随即保存。 */
    fun createFolderForSave(name: String, onCreated: (folderId: String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            fileRepository.createFolder(name.trim(), CopyPolicy.NO_COPY)
                .onSuccess { _saveFolders.value = fileRepository.getFolders(); onCreated(it.id) }
                .onFailure { onError(it.message ?: "新建文件夹失败") }
        }
    }

    /** 接收暂存文件的卡内路径（接收方免保存预览：点媒体气泡直接读暂存区预览）。 */
    fun stagingPathFor(msgId: String): String = p2pManager.stagingPathFor(msgId)

    /** 卡内文件是否还在（预览前判断缓存是否已被 7 天 TTL 清理 → 过期降级）。 */
    suspend fun cardFileExists(path: String): Boolean =
        withContext(Dispatchers.IO) { fileRepository.cardFileExists(path) }

    /**
     * 接收方把暂存文件保存到隐私文件夹：转发 P2PSessionManager.saveReceivedFile（卡内复制暂存→文件夹，暂存保留作缓存）。
     * 成功后刷新会话（气泡转「已保存」、可预览）。
     */
    fun saveReceivedFile(msg: ChatMessage, folderId: String, onResult: (success: Boolean, message: String) -> Unit) {
        val contactId = _currentContactId.value ?: return
        viewModelScope.launch {
            p2pManager.saveReceivedFile(msg.id, contactId, msg.fileName ?: msg.content, folderId)
                .onSuccess { reloadCurrent(contactId); onResult(true, "已保存到文件夹") }
                .onFailure { onResult(false, it.message ?: "保存失败") }
        }
    }

    /**
     * 开/关阅后即焚模式（B 阶段）：转发 P2PSessionManager.setBurnMode（发帧通知对端 + 插系统行）。
     * 系统行插入后由 manager 经 incomingMessages 信号触发当前会话重载，故此处无需手动 reload。
     * onError 用于无连接/发送失败时反馈（仅活动会话内可用）。
     */
    fun setBurnMode(enabled: Boolean, ttlSeconds: Int, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            p2pManager.setBurnMode(enabled, ttlSeconds)
                .onFailure { onError("操作失败：${it.message ?: "需先与对方建立连接"}") }
        }
    }

    /** 删除：仅删本机视图（不通知对端）。 */
    fun deleteMessage(messageId: String) {
        val contactId = _currentContactId.value ?: return
        viewModelScope.launch {
            chatRepo.deleteMessage(messageId, contactId)
            _messages.value = chatRepo.getMessages(contactId)
            _contacts.value = chatRepo.getContacts()
        }
    }

    /**
     * 撤回（M10.5）：有活动会话则发 RECALL 帧令对端一并删除（双方都删）；无连接则退化为本地删除。
     */
    fun recallMessage(messageId: String) {
        val contactId = _currentContactId.value ?: return
        val session = p2pManager.activeSession.value
        viewModelScope.launch {
            if (session != null && session.contactId == contactId) {
                p2pManager.recallMessage(messageId)
            } else {
                chatRepo.markRecalled(messageId, contactId) // 离线退化：仅本地标记已撤回
            }
            reloadCurrent(contactId)
        }
    }

    /** 会话内消息搜索：匹配文字内容与文件名（patch §M6 改动1/3）。 */
    fun searchMessages(contactId: String, query: String): List<ChatMessage> {
        if (query.isBlank()) return chatRepo.getMessages(contactId)
        return chatRepo.getMessages(contactId).filter {
            it.content.contains(query, ignoreCase = true) ||
                it.fileName?.contains(query, ignoreCase = true) == true
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 清空与该联系人的聊天记录（保留联系人）。
     * 接 M1.4 拆分后的 clearMessages（v4/patch 原本调已不存在的 clearContact）。
     */
    fun clearAllMessages(contactId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            chatRepo.clearMessages(contactId)
            _messages.value = emptyList()
            _contacts.value = chatRepo.getContacts()
            // 清完才回调导航：否则调用方立即 onBack/popBackStack 会销毁本 VM 的 scope，
            // 打断 clearMessages 的 delay → 清除半途中断（同 M3.5 忘记密码坑）。
            onComplete()
        }
    }

    /** 修改联系人备注（联系人资料页编辑）。同步写入后重读列表，使会话/列表标题刷新。 */
    fun updateRemark(contactId: String, remark: String) {
        chatRepo.updateRemark(contactId, remark)
        _contacts.value = chatRepo.getContacts()
    }

    /**
     * 删除联系人（连同消息）。接 M1.4 的 deleteContact，由联系人资料页调用。
     * onComplete 在删除落定后回调导航：否则资料页立即 popBackStack 销毁本 VM scope 会打断
     * repo.deleteContact 的 delay → 删除半途中断（同 M3.5 忘记密码 / clearAllMessages 坑）。
     */
    fun deleteContact(contactId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            chatRepo.deleteContact(contactId)
            _contacts.value = chatRepo.getContacts()
            onComplete()
        }
    }

    /**
     * A（出码方）：准备连接——关旧会话 → 生成真实 ConnectionInfo（含本机 IPv6 + 临时 ECDH 公钥）
     * → 后台开始监听对端连入。返回 JSON 供渲染二维码。每次重新出码都会重置会话。
     */
    suspend fun prepareConnection(): String {
        p2pManager.disconnect() // 关旧 ServerSocket/会话，避免端口占用与状态残留
        val info = p2pManager.generateConnectionInfo()
        listenForPeer()
        return info.toJson()
    }

    /** 后台监听对端连入（A=监听方）。握手成功后 connectionState 转 CONNECTED。 */
    private fun listenForPeer() {
        viewModelScope.launch {
            // 监听异常（含 disconnect 主动打断 accept）由 manager 内部归位状态，这里吞掉即可
            runCatching {
                p2pManager.startListening { /* M10.4：握手成功后交换身份、建联系人、收发消息 */ }
            }
        }
    }

    /**
     * B（扫码方）：解析二维码 → 真实 TCP connectTo + ECDH 握手 → **成功才**建链。
     * 联系人由 P2PSessionManager.connectTo 在握手成功后创建（用此处备注 + 二维码 deviceSn），
     * 并启动收发/身份交换；失败经 onError 反馈、不创建联系人。
     */
    fun connectToContact(
        qrContent: String,
        remark: String,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        val info = runCatching { ConnectionInfo.fromJson(qrContent) }.getOrNull()
        if (info == null) {
            onError("二维码格式无效，请确认扫描的是密盾连接码")
            return
        }
        viewModelScope.launch {
            p2pManager.connectTo(info, remark.ifBlank { info.deviceSn })
                .onSuccess {
                    _contacts.value = chatRepo.getContacts()
                    onConnected()
                }
                .onFailure { onError(friendlyConnectError(it, info)) }
        }
    }

    /**
     * 接收方点开焚毁消息（B 阶段）：转发 P2PSessionManager.revealBurnMessage，登记倒计时；
     * 到点由 manager 自动双端焚毁并经 incomingMessages 刷新会话（变焚毁墓碑）。
     */
    fun revealBurnMessage(messageId: String, contactId: String, ttlSeconds: Int) {
        p2pManager.revealBurnMessage(messageId, contactId, ttlSeconds)
    }

    /** 网络诊断快照（真机排障用）：转发 P2PSessionManager 采集的本机 IPv6 信息。 */
    fun networkDiagnostics(): P2PSessionManager.NetworkDiagnostics = p2pManager.networkDiagnostics()

    /**
     * 把底层 socket 异常翻译成可读的连接失败提示，并**附带真实诊断细节**（M10.6 排障强化）：
     * 底层异常类名+message、目标 IPv6、本机出站 IPv6 及是否有公网全局地址。用于真机定位
     * ENETUNREACH（本机/对方无可路由公网 IPv6）vs 超时（对方入站被防火墙拦）vs 拒绝。
     */
    private fun friendlyConnectError(e: Throwable, info: ConnectionInfo): String {
        val diag = p2pManager.networkDiagnostics()
        val cause = when (e) {
            is java.net.SocketTimeoutException ->
                "连接超时：10 秒内目标无响应。SYN 已发出但对方未回——多为对方入站被防火墙拦截，或对方未在监听。"
            else ->
                "连接失败：本机路由到不了目标地址。跨蜂窝网络的公网 IPv6 常被运营商挡死；请两台手机连同一 WiFi 再试。"
        }
        return buildString {
            appendLine(cause)
            appendLine("· 目标地址：${info.ipv6}")
            appendLine("· 本机地址：${diag.selectedAddress}（${diag.kind}）")
            append("· 底层异常：${e.javaClass.simpleName}: ${e.message ?: "无附加信息"}")
        }
    }

    /** 停止当前连接/监听（离开扫码屏且未连上时调用，释放 ServerSocket）。 */
    fun stopConnection() {
        p2pManager.disconnect()
    }
}
