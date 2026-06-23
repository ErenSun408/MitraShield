package com.example.midun.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M12.1 文件加密原语自测（纯 JVM，不依赖 Android）。
 * 覆盖：随机密钥长度、密钥包裹往返、错 KEK 拒解、分块往返/重排/截断/换 nonce 防护、随机 IV。
 */
class FileCryptoTest {

    @Test
    fun randomKey_is32Bytes_andDiffersEachTime() {
        val k1 = FileCrypto.randomKey()
        val k2 = FileCrypto.randomKey()
        assertEquals("AES-256 → 32 字节", 32, k1.size)
        assertNotEquals("两次随机密钥应不同", k1.toList(), k2.toList())
    }

    @Test
    fun wrapUnwrap_roundTripRecoversDek() {
        val kek = FileCrypto.randomKey()
        val dek = FileCrypto.randomKey()
        val wrapped = FileCrypto.wrapKey(kek, dek)
        assertEquals("wrap(KEK,32B) = IV(12)+密文(32)+tag(16) = 60", 60, wrapped.size)
        assertArrayEquals("解包应还原原 DEK", dek, FileCrypto.unwrapKey(kek, wrapped))
    }

    @Test
    fun wrap_randomIvMakesOutputDiffer() {
        val kek = FileCrypto.randomKey()
        val dek = FileCrypto.randomKey()
        assertNotEquals(
            "随机 IV → 同 DEK 每次包裹密文不同",
            FileCrypto.wrapKey(kek, dek).toList(),
            FileCrypto.wrapKey(kek, dek).toList()
        )
    }

    @Test
    fun unwrap_wrongKekRejected() {
        val wrapped = FileCrypto.wrapKey(FileCrypto.randomKey(), FileCrypto.randomKey())
        assertThrows(Exception::class.java) { FileCrypto.unwrapKey(FileCrypto.randomKey(), wrapped) }
    }

    @Test
    fun chunk_roundTripReassemblesFile() {
        val dek = FileCrypto.randomKey()
        val fileNonce = FileCrypto.newFileNonce()
        val chunks = listOf(
            "第一块 chunk-0 ".repeat(100).toByteArray(),
            "第二块 chunk-1 ".repeat(100).toByteArray(),
            "末块（短）tail".toByteArray()
        )
        val out = java.io.ByteArrayOutputStream()
        chunks.forEachIndexed { i, plain ->
            val isLast = i == chunks.lastIndex
            val ct = FileCrypto.encryptChunk(dek, fileNonce, i, isLast, plain)
            out.write(FileCrypto.decryptChunk(dek, fileNonce, i, isLast, ct))
        }
        assertArrayEquals("解密重组应还原整文件", chunks.reduce { a, b -> a + b }, out.toByteArray())
    }

    @Test
    fun chunk_reorderedRejected() {
        val dek = FileCrypto.randomKey()
        val fileNonce = FileCrypto.newFileNonce()
        val c0 = FileCrypto.encryptChunk(dek, fileNonce, 0, isLast = false, "chunk0".toByteArray())
        assertThrows(Exception::class.java) {
            FileCrypto.decryptChunk(dek, fileNonce, 1, isLast = false, c0)
        }
    }

    @Test
    fun chunk_truncationRejected() {
        val dek = FileCrypto.randomKey()
        val fileNonce = FileCrypto.newFileNonce()
        val last = FileCrypto.encryptChunk(dek, fileNonce, 5, isLast = true, "final".toByteArray())
        assertThrows(Exception::class.java) {
            FileCrypto.decryptChunk(dek, fileNonce, 5, isLast = false, last)
        }
    }

    @Test
    fun chunk_wrongFileNonceRejected() {
        val dek = FileCrypto.randomKey()
        val ct = FileCrypto.encryptChunk(dek, FileCrypto.newFileNonce(), 0, isLast = true, "x".toByteArray())
        assertThrows(Exception::class.java) {
            FileCrypto.decryptChunk(dek, FileCrypto.newFileNonce(), 0, isLast = true, ct)
        }
    }
}
