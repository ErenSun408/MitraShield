package com.example.midun.data.real

import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.data.model.UsbDeviceStatus
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 聊天数据快照（联系人 + 按联系人分组的消息），用于真卡持久化整存整取。 */
data class ChatSnapshot(
    val contacts: List<Contact>,
    val messages: Map<String, List<ChatMessage>>
)

/**
 * 聊天记录的真卡持久化后端（M11.5.5）。把 [com.example.midun.data.mock.MockChatRepository] 的内存联系人
 * 与消息序列化为隐藏区侧车 [CHAT_PATH] 的 JSON，使聊天跨会话留存于安全卡。
 *
 * 活动条件 / 依赖选型同 [OperationLogStore]：判据 = [RealUsbManager] AUTHENTICATED（真卡模式 + 盘已打开），
 * 依赖 `RealUsbManager` 叶子而非 `SecurityCardManager`（避免经 MockUsbManager 反向依赖成 DI 环）。
 * 非活动态（模拟模式 / 未认证）下 [load] 回 null、[save] no-op，保留 mock 种子数据开发流。
 */
@Singleton
class ChatStore @Inject constructor(
    private val real: RealFileSystem,
    private val realUsb: RealUsbManager
) {
    private fun active(): Boolean =
        realUsb.deviceStatus.value.status == UsbDeviceStatus.AUTHENTICATED

    suspend fun load(): ChatSnapshot? = withContext(Dispatchers.IO) {
        if (!active()) return@withContext null
        runCatching {
            val out = ByteArrayOutputStream()
            if (real.readFile(CHAT_PATH, out).isFailure) {
                return@runCatching ChatSnapshot(emptyList(), emptyMap()) // 文件不存在=空
            }
            fromJson(out.toString(Charsets.UTF_8.name()))
        }.getOrNull()
    }

    suspend fun save(snapshot: ChatSnapshot) = withContext(Dispatchers.IO) {
        if (!active()) return@withContext
        runCatching { real.writeFile(CHAT_PATH, toJson(snapshot).byteInputStream()) }
        Unit
    }

    private fun toJson(s: ChatSnapshot): String {
        val contacts = JSONArray()
        s.contacts.forEach { contacts.put(contactToJson(it)) }
        val messages = JSONObject()
        s.messages.forEach { (cid, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(messageToJson(it)) }
            messages.put(cid, arr)
        }
        return JSONObject().put("contacts", contacts).put("messages", messages).toString()
    }

    private fun fromJson(text: String): ChatSnapshot {
        val root = JSONObject(text)
        val contactsArr = root.optJSONArray("contacts") ?: JSONArray()
        val contacts = (0 until contactsArr.length()).map { contactFromJson(contactsArr.getJSONObject(it)) }
        val messagesObj = root.optJSONObject("messages") ?: JSONObject()
        val messages = mutableMapOf<String, List<ChatMessage>>()
        for (cid in messagesObj.keys()) {
            val arr = messagesObj.getJSONArray(cid)
            messages[cid] = (0 until arr.length()).map { messageFromJson(arr.getJSONObject(it)) }
        }
        return ChatSnapshot(contacts, messages)
    }

    private fun contactToJson(c: Contact) = JSONObject().apply {
        put("id", c.id); put("deviceId", c.deviceId); put("remark", c.remark)
        put("lastMessage", c.lastMessage); put("lastMessageTime", c.lastMessageTime)
        put("isOnline", c.isOnline); put("unreadCount", c.unreadCount); put("isPinned", c.isPinned)
    }

    private fun contactFromJson(o: JSONObject) = Contact(
        id = o.getString("id"),
        deviceId = o.optString("deviceId"),
        remark = o.optString("remark"),
        lastMessage = o.optString("lastMessage"),
        lastMessageTime = o.optLong("lastMessageTime"),
        isOnline = o.optBoolean("isOnline"),
        unreadCount = o.optInt("unreadCount"),
        isPinned = o.optBoolean("isPinned")
    )

    private fun messageToJson(m: ChatMessage) = JSONObject().apply {
        put("id", m.id); put("contactId", m.contactId); put("content", m.content)
        put("type", m.type.name); put("isMine", m.isMine); put("timestamp", m.timestamp)
        put("status", m.status.name); put("burnAfterRead", m.burnAfterRead)
        m.fileSize?.let { put("fileSize", it) }
        m.fileName?.let { put("fileName", it) }
        put("recalled", m.recalled); put("burnTtl", m.burnTtl); put("burned", m.burned)
        m.savedFolderId?.let { put("savedFolderId", it) }
        m.localPath?.let { put("localPath", it) }
    }

    private fun messageFromJson(o: JSONObject) = ChatMessage(
        id = o.getString("id"),
        contactId = o.getString("contactId"),
        content = o.optString("content"),
        type = runCatching { MessageType.valueOf(o.getString("type")) }.getOrDefault(MessageType.TEXT),
        isMine = o.optBoolean("isMine"),
        timestamp = o.optLong("timestamp"),
        status = runCatching { MessageStatus.valueOf(o.getString("status")) }.getOrDefault(MessageStatus.SENT),
        burnAfterRead = o.optBoolean("burnAfterRead"),
        fileSize = if (o.has("fileSize")) o.getLong("fileSize") else null,
        fileName = if (o.has("fileName")) o.getString("fileName") else null,
        recalled = o.optBoolean("recalled"),
        burnTtl = o.optInt("burnTtl"),
        burned = o.optBoolean("burned"),
        savedFolderId = if (o.has("savedFolderId")) o.getString("savedFolderId") else null,
        localPath = if (o.has("localPath")) o.getString("localPath") else null
    )

    private companion object {
        const val CHAT_PATH = "0:/.midun_chat.json"
    }
}
