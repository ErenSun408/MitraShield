package com.example.midun.data.mock

import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class MockChatRepository @Inject constructor() {

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
        messageId: String? = null
    ): Result<ChatMessage> {
        delay(200)
        val msg = ChatMessage(
            // 联网发送传入稳定 id（两端同 id，供撤回引用）；本地/离线则自生成。
            id = messageId ?: "msg_${System.currentTimeMillis()}",
            contactId = contactId,
            content = content,
            type = type,
            isMine = true,
            status = MessageStatus.SENT
        )
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        updateContactPreview(contactId)
        return Result.success(msg)
    }

    suspend fun deleteMessage(messageId: String, contactId: String): Result<Unit> {
        delay(100)
        mockMessages[contactId]?.removeAll { it.id == messageId }
        updateContactPreview(contactId)
        return Result.success(Unit)
    }

    suspend fun clearMessages(contactId: String): Result<Unit> {
        delay(300)
        mockMessages[contactId]?.clear()
        updateContactPreview(contactId)
        return Result.success(Unit)
    }

    suspend fun deleteContact(contactId: String): Result<Unit> {
        delay(500)
        mockMessages.remove(contactId)
        mockContacts.removeAll { it.id == contactId }
        return Result.success(Unit)
    }

    /** 扫码建联：新增一个联系人。由 P2PSessionManager 建联系人时调用。 */
    fun addContact(contact: Contact) {
        mockContacts.add(contact)
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
        messageId: String? = null
    ): ChatMessage {
        val msg = ChatMessage(
            // 用发送方的稳定 id（供撤回引用）；缺省才自生成。
            id = messageId ?: "msg_${System.currentTimeMillis()}_${(0..9999).random()}",
            contactId = contactId,
            content = content,
            type = type,
            isMine = false,
            status = MessageStatus.RECEIVED
        )
        mockMessages.getOrPut(contactId) { mutableListOf() }.add(msg)
        val idx = mockContacts.indexOfFirst { it.id == contactId }
        if (idx >= 0) {
            mockContacts[idx] = mockContacts[idx].copy(unreadCount = mockContacts[idx].unreadCount + 1)
        }
        updateContactPreview(contactId)
        return msg
    }

    /** 切换联系人置顶状态。由 ChatViewModel.togglePin 调用；置顶项在 getContacts 中排在最前。 */
    fun togglePin(contactId: String) {
        mockContacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> mockContacts[idx] = mockContacts[idx].copy(isPinned = !mockContacts[idx].isPinned) }
    }

    /** 进入会话时清除该联系人的未读计数。由 ChatViewModel.markRead 调用。 */
    fun markContactRead(contactId: String) {
        mockContacts.indexOfFirst { it.id == contactId }
            .takeIf { it >= 0 }
            ?.let { idx -> mockContacts[idx] = mockContacts[idx].copy(unreadCount = 0) }
    }

    /** 整卡擦除时调用：清空所有联系人与消息。由 MockUsbManager.wipeAll() 统一触发。 */
    fun clear() {
        mockContacts.clear()
        mockMessages.clear()
    }

    private fun updateContactPreview(contactId: String) {
        val index = mockContacts.indexOfFirst { it.id == contactId }
        if (index == -1) return

        val lastMessage = mockMessages[contactId]
            ?.maxByOrNull { it.timestamp }

        mockContacts[index] = mockContacts[index].copy(
            lastMessage = lastMessage?.content.orEmpty(),
            lastMessageTime = lastMessage?.timestamp ?: 0L
        )
    }
}
