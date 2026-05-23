package com.example.midun.data.model

enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, FILE }

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
    val fileName: String? = null
)

data class Contact(
    val id: String,
    val deviceId: String,
    val remark: String,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val isOnline: Boolean = false,
    val unreadCount: Int = 0
)