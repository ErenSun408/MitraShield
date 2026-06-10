package com.example.midun.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M10.2/M10.9 加解密自测（纯 JVM，不依赖 Android / socket）。
 * 验证压缩公钥往返、ECDH 双方派生同一密钥、AES-GCM 往返、随机 IV、tag 防篡改、错误密钥拒解。
 */
class P2PCryptoTest {

    private fun freshSharedKey(): ByteArray {
        val a = P2PCrypto.generateEcKeyPair()
        val b = P2PCrypto.generateEcKeyPair()
        return P2PCrypto.deriveSharedKey(a.private, P2PCrypto.compressPublicKey(b.public))
    }

    @Test
    fun ecdh_bothSidesDeriveSameKey() {
        val a = P2PCrypto.generateEcKeyPair()
        val b = P2PCrypto.generateEcKeyPair()
        val keyFromA = P2PCrypto.deriveSharedKey(a.private, P2PCrypto.compressPublicKey(b.public))
        val keyFromB = P2PCrypto.deriveSharedKey(b.private, P2PCrypto.compressPublicKey(a.public))
        assertArrayEquals("双方 ECDH 应派生相同会话密钥", keyFromA, keyFromB)
        assertEquals("AES-256 → 32 字节", 32, keyFromA.size)
    }

    @Test
    fun compressedPublicKey_is33BytesWithValidPrefix() {
        repeat(20) {
            val compressed = P2PCrypto.compressPublicKey(P2PCrypto.generateEcKeyPair().public)
            assertEquals("压缩公钥应为 33 字节", 33, compressed.size)
            val prefix = compressed[0].toInt() and 0xFF
            assertTrue("前缀应为 0x02/0x03，实际 $prefix", prefix == 0x02 || prefix == 0x03)
        }
    }

    @Test
    fun compressDecompress_roundTripRecoversSamePoint() {
        // 多跑几轮覆盖 Y 奇偶两种前缀，确保点解压（模平方根）实现正确。
        repeat(50) {
            val original = P2PCrypto.generateEcKeyPair().public
            val recovered = P2PCrypto.decompressPublicKey(P2PCrypto.compressPublicKey(original))
            assertArrayEquals("解压后公钥应与原始一致", original.encoded, recovered.encoded)
        }
    }

    @Test
    fun aesGcm_roundTrip() {
        val key = freshSharedKey()
        val plaintext = "密盾 P2P 测试消息 🔐 hello"
        val cipher = P2PCrypto.encrypt(key, plaintext)
        assertEquals(plaintext, P2PCrypto.decrypt(key, cipher))
    }

    @Test
    fun aesGcm_randomIvMakesCiphertextDiffer() {
        val key = freshSharedKey()
        val c1 = P2PCrypto.encrypt(key, "same plaintext")
        val c2 = P2PCrypto.encrypt(key, "same plaintext")
        assertNotEquals("随机 IV 应使同明文每次密文不同", c1.toList(), c2.toList())
    }

    @Test
    fun aesGcm_tamperedCiphertextRejected() {
        val key = freshSharedKey()
        val cipher = P2PCrypto.encrypt(key, "secret")
        cipher[cipher.size - 1] = (cipher[cipher.size - 1] + 1).toByte()
        assertThrows(Exception::class.java) { P2PCrypto.decrypt(key, cipher) }
    }

    @Test
    fun aesGcm_wrongKeyRejected() {
        val cipher = P2PCrypto.encrypt(freshSharedKey(), "secret")
        assertThrows(Exception::class.java) { P2PCrypto.decrypt(freshSharedKey(), cipher) }
    }
}
