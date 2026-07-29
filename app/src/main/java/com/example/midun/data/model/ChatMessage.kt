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
    // 焚毁 TTL（秒）：阅后即焚消息「读到」后多久焚。时长不可选，各类型一律 0（见 P2PSessionManager.burnTtlFor）
    // ——文字关闭弹窗、语音听完、图/视频/文件关闭预览，都当场焚。非焚毁消息也是 0，故判据一律看 burnAfterRead。
    val burnTtl: Int = 0,
    // 已焚毁标记（历史遗留）：焚毁已改「整条删除、彻底不留痕」，不再产生墓碑；保留字段仅为兼容旧数据
    // 与 displayMessages 的过滤兜底（登录时 purgeBurnRemnants 会清掉遗留的 burned 记录）。
    val burned: Boolean = false,
    // 焚毁死线（epoch ms，null=未定死线）：**持久化**的绝对时刻，到点本端必焚。来源=接收方点开焚毁消息
    // （now + burnTtl）。落盘的意义：进程被杀会丢内存计时器，重认证后据此补焚（已过期立刻焚、未过期续挂计时）。
    // 发送方自己那份不设死线——登录时由 ChatRepository.purgeBurnRemnants 统一清除。
    val burnDeadline: Long? = null,
    // 文件消息（M11.5.3 file-transfer）：接收方保存后落到的隐私文件夹路径。
    // null + type=FILE + isMine=false = 已收到、暂存在卡内 0:/.recv_<id>、待用户点击选文件夹保存。
    val savedFolderId: String? = null,
    // 发送方自己可预览的卡内路径（file-transfer 阶段2）：手机来源=发送时留的副本 0:/.sent_<id>，
    // 隐私文件夹来源=源文件卡内路径。null=无副本（非媒体/留副本失败）→ 发送方不可预览。
    val localPath: String? = null,
    // 「去建立连接」系统提示行（type=SYSTEM 时有效）：未建立会话时发送消息后插入的一条提示，
    // 渲染为带可点链接的居中系统行；每个断连段只插一条、作为历史记录保留。
    val connectPrompt: Boolean = false,
    // 语音消息（type=AUDIO，[chat-voice]）：录音时长（秒），气泡显时长 + 决定气泡宽度。音频文件本体复用文件
    // 传输缓存（接收=0:/.recv_<id>、发送=0:/.sent_<id>），即收即播、不进「选文件夹保存」流程。0=非语音。
    val audioDurationSec: Int = 0
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