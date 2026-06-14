package com.example.midun.data.model

enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, FILE, SYSTEM }

enum class MessageStatus { SENDING, SENT, RECEIVED, FAILED }

data class ChatMessage(
    val id: String,
    val contactId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val isMine: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val burnAfterRead: Boolean = false,
    val fileSize: Long? = null,
    val fileName: String? = null,
    val recalled: Boolean = false,
    // 焚毁 TTL（秒）：阅后即焚消息「读到」后的倒计时时长。0 = 非焚毁消息。
    val burnTtl: Int = 0,
    // 已焚毁标记（阅后即焚墓碑），与 recalled 同为「原地把真实消息变残骸」，渲染为焚毁墓碑。
    val burned: Boolean = false,
    // 文件消息（M11.5.3 file-transfer）：接收方保存后落到的隐私文件夹路径。
    // null + type=FILE + isMine=false = 已收到、暂存在卡内 0:/.recv_<id>、待用户点击选文件夹保存。
    val savedFolderId: String? = null,
    // 发送方自己可预览的卡内路径（file-transfer 阶段2）：手机来源=发送时留的副本 0:/.sent_<id>，
    // 隐私文件夹来源=源文件卡内路径。null=无副本（模拟模式/非媒体/留副本失败）→ 发送方不可预览。
    val localPath: String? = null
)

data class Contact(
    val id: String,
    val deviceId: String,
    val remark: String,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val isOnline: Boolean = false,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)