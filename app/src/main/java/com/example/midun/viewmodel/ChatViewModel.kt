package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.mock.MockChatRepository
import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.network.ConnectionInfo
import com.example.midun.network.P2PSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: MockChatRepository,
    private val p2pManager: P2PSessionManager
) : ViewModel() {

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

    /**
     * 删除联系人（连同消息）。接 M1.4 的 deleteContact。
     * M6 patch UI 暂无入口，先预留供后续"删除联系人"动作接入。
     */
    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            chatRepo.deleteContact(contactId)
            _contacts.value = chatRepo.getContacts()
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
                .onFailure { onError(friendlyConnectError(it)) }
        }
    }

    /** 把底层 socket 异常翻译成可读的中文连接失败提示（M10.6）。 */
    private fun friendlyConnectError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException ->
            "连接超时：对方可能不在线，或当前网络无法直达。建议双方改用移动数据（4G/5G）。"
        else ->
            "无法连接：网络不可达或对方未在等待。请确认对方已生成二维码，且双方均在移动数据（公网 IPv6）下。"
    }

    /** 停止当前连接/监听（离开扫码屏且未连上时调用，释放 ServerSocket）。 */
    fun stopConnection() {
        p2pManager.disconnect()
    }
}
