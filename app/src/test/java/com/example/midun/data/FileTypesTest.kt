package com.example.midun.data

import com.example.midun.data.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M-files 类型判定自测（纯 JVM）：健壮扩展名、内容特征码、综合判定（内容压过歪名）、稳定编码往返。 */
class FileTypesTest {

    @Test
    fun fromExtension_stripsTrailingJunk() {
        assertEquals(FileType.VIDEO, FileTypes.fromExtension("movie.mp4(3)"))
        assertEquals(FileType.VIDEO, FileTypes.fromExtension("movie.MP4 (2)"))
        assertEquals(FileType.IMAGE, FileTypes.fromExtension("photo (1).JPG"))
        assertEquals(FileType.IMAGE, FileTypes.fromExtension("a.png"))
        assertEquals(FileType.DOCUMENT, FileTypes.fromExtension("报告.docx"))
        assertEquals(FileType.OTHER, FileTypes.fromExtension("noext"))
        assertEquals(FileType.OTHER, FileTypes.fromExtension("data.bin"))
    }

    @Test
    fun fromContent_recognizesSignatures() {
        assertEquals(FileType.IMAGE, FileTypes.fromContent(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)))
        assertEquals(
            FileType.IMAGE,
            FileTypes.fromContent(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        )
        assertEquals(FileType.DOCUMENT, FileTypes.fromContent("%PDF-1.7".toByteArray()))
        assertEquals(FileType.VIDEO, FileTypes.fromContent("....ftypisom".toByteArray()))
        assertEquals(FileType.AUDIO, FileTypes.fromContent("....ftypM4A ".toByteArray()))
        assertEquals(FileType.IMAGE, FileTypes.fromContent("....ftypheic".toByteArray()))
        assertEquals(FileType.IMAGE, FileTypes.fromContent("RIFF????WEBP".toByteArray()))
        assertEquals(FileType.VIDEO, FileTypes.fromContent("RIFF????AVI ".toByteArray()))
        assertEquals(FileType.AUDIO, FileTypes.fromContent("ID3etcetc".toByteArray()))
        assertEquals(FileType.VIDEO, FileTypes.fromContent(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertNull("通用 zip 含糊 → 交扩展名", FileTypes.fromContent("PK".toByteArray()))
        assertNull("无特征码 → null", FileTypes.fromContent("just plain text".toByteArray()))
    }

    @Test
    fun detect_contentOverridesWrongName() {
        // 文件名歪（.mp4(3)）但内容是 PNG → 应判 IMAGE（内容优先）。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(FileType.IMAGE, FileTypes.detect(png, "weird.mp4(3)"))
        // 内容判不出（docx 的 zip 头）→ 退回扩展名 DOCUMENT。
        assertEquals(FileType.DOCUMENT, FileTypes.detect("PK".toByteArray(), "report.docx(2)"))
    }

    @Test
    fun code_roundTrip() {
        for (t in FileType.entries) assertEquals(t, FileTypes.fromCode(FileTypes.toCode(t)))
        assertEquals(FileType.OTHER, FileTypes.fromCode(99))
    }
}
