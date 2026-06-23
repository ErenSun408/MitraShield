package com.example.midun.crypto

import com.example.midun.data.model.FileType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12.2/M-files 文件头自测（纯 JVM）：往返（含类型）、长度、MAGIC/版本/长度不符拒解、密文大小反推。 */
class FileHeaderTest {

    @Test
    fun build_isFixed22Bytes() {
        assertEquals(22, FileHeader.BYTES)
        assertEquals(22, FileHeader.build(0, FileCrypto.newFileNonce(), FileType.OTHER).size)
    }

    @Test
    fun buildParse_roundTrip() {
        val nonce = FileCrypto.newFileNonce()
        val parsed = FileHeader.parse(FileHeader.build(1234567L, nonce, FileType.VIDEO))!!
        assertEquals(1234567L, parsed.plaintextSize)
        assertArrayEquals(nonce, parsed.fileNonce)
        assertEquals(FileType.VIDEO, parsed.fileType)
    }

    @Test
    fun parse_rejectsBadMagic() {
        val h = FileHeader.build(10, FileCrypto.newFileNonce(), FileType.IMAGE)
        h[0] = 'X'.code.toByte()
        assertNull(FileHeader.parse(h))
    }

    @Test
    fun parse_rejectsBadVersion() {
        val h = FileHeader.build(10, FileCrypto.newFileNonce(), FileType.IMAGE)
        h[4] = 99
        assertNull(FileHeader.parse(h))
    }

    @Test
    fun parse_rejectsTooShort() {
        assertNull(FileHeader.parse(ByteArray(10)))
        assertNull(FileHeader.parse(FileHeader.MAGIC))
    }

    @Test
    fun cipherToPlaintextSize_matchesLayout() {
        // 明文 200KB → ceil(200KB/块) 块，每块 +16B tag，加文件头。块大小取单一来源，避免随之改动失配。
        val plain = 200L * 1024
        val chunkSize = FileCrypto.CHUNK_PLAIN_BYTES
        val chunks = (plain + chunkSize - 1) / chunkSize
        val cipher = FileHeader.BYTES + plain + chunks * 16
        assertTrue(FileHeader.cipherToPlaintextSize(cipher, plain))
        assertFalse("少一字节应不匹配", FileHeader.cipherToPlaintextSize(cipher - 1, plain))
    }

    @Test
    fun cipherToPlaintextSize_emptyFileHasOneTagChunk() {
        // 空文件：1 块（纯 16B tag）+ 文件头。
        assertTrue(FileHeader.cipherToPlaintextSize(FileHeader.BYTES.toLong() + 16, 0L))
    }
}
