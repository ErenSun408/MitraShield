package com.example.midun.data.model

/**
 * 操作类型。`label` 用于首页「最近操作」卡的标题展示。
 *
 * 注意：这是 v4 doc 外的增量功能（v4/patch 全文无操作日志设计，见 docs/design-deviations.md「M9.3」）。
 * mock 期日志仅存内存、不持久化；M11 接真实 SDK 时写安全卡 EMMC。
 */
enum class OperationType(val label: String) {
    LOGIN("登录认证"),
    FILE_IMPORT("文件导入"),
    FILE_EXPORT("文件导出"),
    FILE_MOVE("文件移动"),
    FILE_DELETE("文件删除"),
    FOLDER_CREATE("新建文件夹"),
    FILE_RENAME("重命名"),
    KEY_UPDATE("密钥更新"),
    CONNECT("相逢叙话"),
}

/**
 * 一条操作记录。[timestamp] 为 `System.currentTimeMillis()`，由展示层格式化为 HH:mm。
 * 清理类操作（一键清理/恢复出厂）本身会清空日志，故**不记录自身**（见 M9.3 偏离）。
 */
data class OperationLog(
    val type: OperationType,
    val description: String,
    val timestamp: Long
)
