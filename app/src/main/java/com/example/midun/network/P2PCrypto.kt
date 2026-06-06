package com.example.midun.network

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P2P 加密原语（M10.2，B 方案核心）。纯 `java.security`/`javax.crypto`，**无 Android 依赖**，
 * 可在 JVM 单测直接运行（见 `P2PCryptoTest`）。
 *
 * - ECDH(P-256) 协商共享密钥 → HKDF-SHA256 派生 AES-256 会话密钥
 * - AES-256-GCM 加解密（替换 v4 §9.3 的 `mockEncrypt = Base64` 假加密）
 *
 * 偏离 v4：v4 把 ECDH/加密留给 FSShell（`// TODO 调用FSShell` + Base64 mock）；本类用标准库
 * 提前实现真加密，M10 端到端真加密（密钥来自软件）；M11 再把软件 ECDH 换成 FSShell 安全卡硬件密钥。
 *
 * Base64 不在本类做——本类只收发 `ByteArray`，故能脱离 Android `android.util.Base64`（JVM 单测里
 * 是抛异常的桩）独立测试；Base64 包装在 [P2PSessionManager] 的传输边界用 `android.util.Base64`。
 */
object P2PCrypto {

    private const val EC_CURVE = "secp256r1" // NIST P-256
    private const val AES_KEY_BYTES = 32 // AES-256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val HKDF_INFO = "MiDun-P2P-AES256".toByteArray(Charsets.UTF_8)

    fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(EC_CURVE))
        }.generateKeyPair()

    /** ECDH 协商 + HKDF 派生 32 字节 AES 密钥。[peerPublicKeyBytes] = 对端公钥 X.509(SPKI) 编码。 */
    fun deriveSharedKey(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPublic = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(peerPublic, true)
        }
        return hkdfSha256(agreement.generateSecret(), AES_KEY_BYTES)
    }

    /** AES-256-GCM 加密；输出 = IV(12B) ‖ 密文+tag。每次随机 IV。 */
    fun encrypt(key: ByteArray, plaintext: String): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /** AES-256-GCM 解密；输入 = IV(12B) ‖ 密文+tag。tag/密钥不符抛 `AEADBadTagException`。 */
    fun decrypt(key: ByteArray, data: ByteArray): String {
        require(data.size > GCM_IV_BYTES) { "密文长度不足" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    /** RFC 5869 HKDF-SHA256（salt 取全零，单块输出，要求 length ≤ 32）。 */
    private fun hkdfSha256(ikm: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract：PRK = HMAC(salt=zeros, IKM)
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand：T(1) = HMAC(PRK, info ‖ 0x01)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(HKDF_INFO)
        mac.update(0x01.toByte())
        return mac.doFinal().copyOf(length)
    }
}
