package com.example.midun.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M10.2 加解密自测（纯 JVM，不依赖 Android / socket）。
 * 验证 ECDH 双方派生同一密钥、AES-GCM 往返、随机 IV、tag 防篡改、错误密钥拒解。
 */
class P2PCryptoTest {

    private fun freshSharedKey(): ByteArray {
        val a = P2PCrypto.generateEcKeyPair()
        val b = P2PCrypto.generateEcKeyPair()
        return P2PCrypto.deriveSharedKey(a.private, b.public.encoded)
    }

    @Test
    fun ecdh_bothSidesDeriveSameKey() {
        val a = P2PCrypto.generateEcKeyPair()
        val b = P2PCrypto.generateEcKeyPair()
        val keyFromA = P2PCrypto.deriveSharedKey(a.private, b.public.encoded)
        val keyFromB = P2PCrypto.deriveSharedKey(b.private, a.public.encoded)
        assertArrayEquals("双方 ECDH 应派生相同会话密钥", keyFromA, keyFromB)
        assertEquals("AES-256 → 32 字节", 32, keyFromA.size)
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
