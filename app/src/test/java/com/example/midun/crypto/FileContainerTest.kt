package com.example.midun.crypto

import com.example.midun.crypto.FileContainer.BadPassphraseException
import com.example.midun.data.model.FileType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import org.junit.Test

/**
 * 导出容器（M12.5）纯 JVM 自测：PBKDF2 对已知向量、加解密往返、错口令/损坏/截断的拒绝。
 */
class FileContainerTest {

    /** PBKDF2-HMAC-SHA256 已知向量（dkLen=32），手写实现须逐字节吻合。 */
    @Test
    fun pbkdf2MatchesKnownVectors() {
        val salt = "salt".toByteArray(Charsets.UTF_8)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(FileContainer.deriveKey("password", salt, 1))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(FileContainer.deriveKey("password", salt, 2))
        )
    }

    @Test
    fun roundTripRecoversPlaintextAndMeta() {
        val plain = Random(11).nextBytes(3 * FileCrypto.CHUNK_PLAIN_BYTES + 777) // 多块 + 末块不足整块
        val container = encrypt(plain, "合同 v3.pdf", FileType.DOCUMENT, "hunter2")

        // 头部 MAGIC 可被识别为容器
        assertTrue(FileContainer.isContainer(container.copyOf(8)))

        val out = ByteArrayOutputStream()
        val meta = FileContainer.decrypt(ByteArrayInputStream(container), "hunter2", out)
        assertArrayEquals(plain, out.toByteArray())
        assertEquals("合同 v3.pdf", meta.originalName)
        assertEquals(FileType.DOCUMENT, meta.fileType)
        assertEquals(plain.size.toLong(), meta.plaintextSize)
    }

    @Test
    fun emptyFileRoundTrips() {
        val container = encrypt(ByteArray(0), "空.txt", FileType.OTHER, "pw")
        val out = ByteArrayOutputStream()
        val meta = FileContainer.decrypt(ByteArrayInputStream(container), "pw", out)
        assertEquals(0, out.toByteArray().size)
        assertEquals(0L, meta.plaintextSize)
    }

    @Test
    fun wrongPassphraseRejected() {
        val container = encrypt(Random(2).nextBytes(5000), "a.bin", FileType.OTHER, "correct")
        try {
            FileContainer.decrypt(ByteArrayInputStream(container), "wrong", ByteArrayOutputStream())
            fail("错口令应抛 BadPassphraseException")
        } catch (e: BadPassphraseException) {
            // 预期
        }
    }

    @Test
    fun tamperedCiphertextRejected() {
        val container = encrypt(Random(3).nextBytes(5000), "a.bin", FileType.OTHER, "pw")
        container[container.size - 1] = (container[container.size - 1] + 1).toByte() // 改末字节
        try {
            FileContainer.decrypt(ByteArrayInputStream(container), "pw", ByteArrayOutputStream())
            fail("篡改应被 GCM 校验拒绝")
        } catch (e: BadPassphraseException) {
            // 预期（GCM tag 失败）
        }
    }

    @Test
    fun nonContainerRejected() {
        try {
            FileContainer.decrypt(ByteArrayInputStream(Random(4).nextBytes(100)), "pw", ByteArrayOutputStream())
            fail("非容器字节应抛 IOException")
        } catch (e: IOException) {
            // 预期（MAGIC 不符 / 截断）
        }
        assertFalse(FileContainer.isContainer("XXXX".toByteArray()))
    }

    private fun encrypt(plain: ByteArray, name: String, type: FileType, pass: String): ByteArray {
        val out = ByteArrayOutputStream()
        FileContainer.encrypt(ByteArrayInputStream(plain), plain.size.toLong(), name, type, pass, out)
        return out.toByteArray()
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
