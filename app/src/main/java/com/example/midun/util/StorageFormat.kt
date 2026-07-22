package com.example.midun.util

import java.util.Locale

/**
 * 字节数 → 人类可读容量字符串（M11.6.1）。`<=0` 视为未知，返回「--」。GB/MB/KB 自动选档。
 */
fun formatStorage(bytes: Long): String = when {
    bytes <= 0L -> "--"
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.CHINA, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.CHINA, bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(Locale.CHINA, bytes / 1024.0)
}

/**
 * 粗粒度容量（只用于「已用空间」）：**不足 1MB 一律显示「0 MB」**（向下取整到整 MB），
 * 避免每次登录写操作日志等 KB 级开销让数字微跳。够 1GB 进 GB 档，否则整 MB 档。
 * 调用方须自行保证已认证（本函数不判未知态、0 也显示「0 MB」）。
 */
fun formatStorageCoarse(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.CHINA, bytes / (1024.0 * 1024 * 1024))
    else -> "${(bytes shr 20).coerceAtLeast(0L)} MB"
}
