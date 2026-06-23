package com.example.midun.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12.2 文件头自测（纯 JVM）：往返、长度、MAGIC/版本/长度不符拒解、密文大小反推。 */
class FileHeaderTest {

    @Test
    fun build_isFixed21Bytes() {
        assertEquals(21, FileHeader.BYTES)
        assertEquals(21, FileHeader.build(0, FileCrypto.newFileNonce()).size)
    }

    @Test
    fun buildParse_roundTrip() {
        val nonce = FileCrypto.newFileNonce()
        val size = 1234567L
        val parsed = FileHeader.parse(FileHeader.build(size, nonce))!!
        assertEquals(size, parsed.plaintextSize)
        assertArrayEquals(nonce, parsed.fileNonce)
    }

    @Test
    fun parse_rejectsBadMagic() {
        val h = FileHeader.build(10, FileCrypto.newFileNonce())
        h[0] = 'X'.code.toByte()
        assertNull(FileHeader.parse(h))
    }

    @Test
    fun parse_rejectsBadVersion() {
        val h = FileHeader.build(10, FileCrypto.newFileNonce())
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
        // 明文 200KB → ceil(200KB/块) 块，每块 +16B tag，加 21B 头。块大小取单一来源，避免随之改动失配。
        val plain = 200L * 1024
        val chunkSize = FileCrypto.CHUNK_PLAIN_BYTES
        val chunks = (plain + chunkSize - 1) / chunkSize
        val cipher = FileHeader.BYTES + plain + chunks * 16
        assertTrue(FileHeader.cipherToPlaintextSize(cipher, plain))
        assertFalse("少一字节应不匹配", FileHeader.cipherToPlaintextSize(cipher - 1, plain))
    }

    @Test
    fun cipherToPlaintextSize_emptyFileHasOneTagChunk() {
        // 空文件：1 块（纯 16B tag）+ 21B 头。
        assertTrue(FileHeader.cipherToPlaintextSize(FileHeader.BYTES.toLong() + 16, 0L))
    }
}
