package com.example.midun.data

import com.example.midun.data.model.FileType

/**
 * 文件类型判定（M-files）。两条路：
 * - [fromContent]：按内容 magic number（文件特征码）判型，**完全无视文件名** → 对 `movie.mp4(3)`、改名、
 *   无后缀都免疫。导入时用它，结果存进 [com.example.midun.crypto.FileHeader]。
 * - [fromExtension]：健壮扩展名解析（剥掉 `(3)`、空格等尾巴），作内容判不出时的兜底。
 *
 * 还提供 [toCode]/[fromCode]：FileType ↔ 稳定字节码，供文件头持久化（**不随枚举顺序变**）。
 *
 * 纯逻辑、无 Android 依赖 → JVM 可测（见 `FileTypesTest`）。
 */
object FileTypes {

    /** 综合判型：先看内容特征码，识别不出再退回扩展名。[head] = 文件前若干字节（明文）。 */
    fun detect(head: ByteArray, name: String): FileType = fromContent(head) ?: fromExtension(name)

    /** 健壮扩展名判型：取「真正的扩展名」= 最后一个点后的**前导字母数字串**（`mp4(3)`→`mp4`、`MP4 (2)`→`mp4`）。 */
    fun fromExtension(name: String): FileType {
        val ext = name.substringAfterLast('.', "").lowercase().takeWhile { it.isLetterOrDigit() }
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> FileType.IMAGE
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v" -> FileType.VIDEO
            "mp3", "aac", "wav", "m4a", "flac", "ogg" -> FileType.AUDIO
            "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx" -> FileType.DOCUMENT
            else -> FileType.OTHER
        }
    }

    /**
     * 内容特征码判型；识别不出 / 含糊（如通用 zip）返回 null，交由扩展名兜底。[head] 取文件前 ≥16 字节最佳。
     */
    fun fromContent(head: ByteArray): FileType? {
        // —— 图片 ——
        if (match(head, 0, 0xFF, 0xD8, 0xFF)) return FileType.IMAGE // JPEG
        if (match(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return FileType.IMAGE // PNG
        if (ascii(head, 0, "GIF8")) return FileType.IMAGE // GIF87a/GIF89a
        if (ascii(head, 0, "BM")) return FileType.IMAGE // BMP
        // —— RIFF 容器：WEBP / AVI / WAVE ——
        if (ascii(head, 0, "RIFF")) return when {
            ascii(head, 8, "WEBP") -> FileType.IMAGE
            ascii(head, 8, "AVI ") -> FileType.VIDEO
            ascii(head, 8, "WAVE") -> FileType.AUDIO
            else -> null
        }
        // —— ISO BMFF：mp4/mov/m4a/3gp/heic（偏移 4 处 "ftyp"，brand 在 8）——
        if (ascii(head, 4, "ftyp")) return when {
            ascii(head, 8, "M4A ") -> FileType.AUDIO
            ascii(head, 8, "heic") || ascii(head, 8, "heif") || ascii(head, 8, "mif1") -> FileType.IMAGE
            else -> FileType.VIDEO
        }
        // —— Matroska / WebM ——
        if (match(head, 0, 0x1A, 0x45, 0xDF, 0xA3)) return FileType.VIDEO
        // —— 音频 ——
        if (ascii(head, 0, "ID3")) return FileType.AUDIO // 带 ID3 标签的 MP3
        if (head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) {
            return FileType.AUDIO // MP3 帧同步
        }
        if (ascii(head, 0, "fLaC")) return FileType.AUDIO
        if (ascii(head, 0, "OggS")) return FileType.AUDIO
        // —— 文档 ——
        if (ascii(head, 0, "%PDF")) return FileType.DOCUMENT
        // PK(zip/office) 含糊 → null，交扩展名（docx/xlsx 等）
        return null
    }

    /** FileType → 稳定存储字节码（写文件头）。 */
    fun toCode(type: FileType): Int = when (type) {
        FileType.FOLDER -> 0
        FileType.IMAGE -> 1
        FileType.VIDEO -> 2
        FileType.AUDIO -> 3
        FileType.DOCUMENT -> 4
        FileType.OTHER -> 5
    }

    /** 稳定存储字节码 → FileType（未知码归 OTHER）。 */
    fun fromCode(code: Int): FileType = when (code) {
        0 -> FileType.FOLDER
        1 -> FileType.IMAGE
        2 -> FileType.VIDEO
        3 -> FileType.AUDIO
        4 -> FileType.DOCUMENT
        else -> FileType.OTHER
    }

    private fun match(b: ByteArray, off: Int, vararg sig: Int): Boolean {
        if (b.size < off + sig.size) return false
        for (i in sig.indices) if ((b[off + i].toInt() and 0xFF) != sig[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, off: Int, s: String): Boolean {
        if (b.size < off + s.length) return false
        for (i in s.indices) if ((b[off + i].toInt() and 0xFF) != s[i].code) return false
        return true
    }
}
