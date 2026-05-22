package com.example.midun.data

data class SecureFolder(
    val id: String,
    val name: String,
    val fileCount: Int,
    val createdTime: String,
    val allowCopy: Boolean = false,
    val copyMode: String = "不可拷贝" // 不可拷贝/拷贝明文/拷贝密文
)

data class SecureFile(
    val id: String,
    val name: String,
    val size: String,
    val type: FileType,
    val createdTime: String,
    val source: String // 导入/聊天记录
)

enum class FileType(val label: String) {
    DOCUMENT("文档"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"), OTHER("其他")
}

data class Contact(
    val id: String,
    val name: String,
    val deviceId: String,
    val lastMessage: String,
    val lastTime: String,
    val unreadCount: Int = 0
)

data class ChatMessage(
    val id: String,
    val content: String,
    val type: MessageType,
    val isMine: Boolean,
    val time: String,
    val burnAfterRead: Boolean = false
)

enum class MessageType { TEXT, IMAGE, VIDEO, FILE, VOICE }

object MockData {
    val folders = listOf(
        SecureFolder("1", "工作文档", 12, "2026-05-01 10:30", true, "拷贝密文"),
        SecureFolder("2", "私人照片", 36, "2026-04-20 14:20", false),
        SecureFolder("3", "合同资料", 8, "2026-05-10 09:15", true, "拷贝明文"),
        SecureFolder("4", "财务报表", 5, "2026-03-15 16:40", false),
    )

    val files = mapOf(
        "1" to listOf(
            SecureFile("f1", "项目方案.pdf", "2.3MB", FileType.DOCUMENT, "2026-05-01 11:00", "导入"),
            SecureFile("f2", "会议纪要.docx", "156KB", FileType.DOCUMENT, "2026-05-02 09:30", "导入"),
            SecureFile("f3", "产品设计图.png", "4.8MB", FileType.IMAGE, "2026-05-03 14:20", "聊天记录"),
            SecureFile("f4", "演示视频.mp4", "28MB", FileType.VIDEO, "2026-05-05 16:00", "导入"),
        ),
        "2" to listOf(
            SecureFile("f5", "旅行照片01.jpg", "3.2MB", FileType.IMAGE, "2026-04-20 15:00", "导入"),
            SecureFile("f6", "旅行照片02.jpg", "2.8MB", FileType.IMAGE, "2026-04-20 15:05", "导入"),
            SecureFile("f7", "家庭视频.mp4", "56MB", FileType.VIDEO, "2026-04-21 10:30", "导入"),
        ),
        "3" to listOf(
            SecureFile("f8", "租赁合同.pdf", "1.1MB", FileType.DOCUMENT, "2026-05-10 09:20", "导入"),
            SecureFile("f9", "保密协议.pdf", "890KB", FileType.DOCUMENT, "2026-05-10 09:25", "聊天记录"),
        ),
        "4" to listOf(
            SecureFile("f10", "Q1报表.xlsx", "456KB", FileType.DOCUMENT, "2026-03-15 17:00", "导入"),
        ),
    )

    val contacts = listOf(
        Contact("c1", "张三", "DEV-A1B2C3", "收到，明天发你", "10:30", 2),
        Contact("c2", "李四", "DEV-D4E5F6", "文件已发送", "昨天", 0),
        Contact("c3", "王五", "DEV-G7H8I9", "[文件] 合同草案.pdf", "周一", 0),
        Contact("c4", "赵六", "DEV-J0K1L2", "[图片]", "05-08", 1),
    )

    val chatMessages = mapOf(
        "c1" to listOf(
            ChatMessage("m1", "你好，项目资料准备好了吗？", MessageType.TEXT, false, "10:15"),
            ChatMessage("m2", "准备好了，我现在发给你", MessageType.TEXT, true, "10:20"),
            ChatMessage("m3", "[文件] 项目方案v2.pdf (2.3MB)", MessageType.FILE, true, "10:22"),
            ChatMessage("m4", "收到，明天发你", MessageType.TEXT, false, "10:30"),
        ),
        "c2" to listOf(
            ChatMessage("m5", "帮我看下这个设计图", MessageType.TEXT, false, "昨天 09:00"),
            ChatMessage("m6", "[图片] 产品设计图.png", MessageType.IMAGE, false, "昨天 09:01"),
            ChatMessage("m7", "没问题，细节处理得不错", MessageType.TEXT, true, "昨天 09:15"),
            ChatMessage("m8", "文件已发送", MessageType.TEXT, false, "昨天 09:20"),
        ),
        "c3" to listOf(
            ChatMessage("m9", "合同草案发你审阅", MessageType.TEXT, false, "周一 14:00"),
            ChatMessage("m10", "[文件] 合同草案.pdf (1.1MB)", MessageType.FILE, false, "周一 14:01"),
            ChatMessage("m11", "好的，我看看", MessageType.TEXT, true, "周一 14:30"),
        ),
    )
}