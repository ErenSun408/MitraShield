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
