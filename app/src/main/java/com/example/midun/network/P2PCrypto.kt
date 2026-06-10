package com.example.midun.network

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
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
    private const val FIELD_BYTES = 32 // P-256 坐标字节数（256 位）
    private const val COMPRESSED_LEN = 33 // 1 字节奇偶前缀 + 32 字节 X 坐标
    private val HKDF_INFO = "MiDun-P2P-AES256".toByteArray(Charsets.UTF_8)

    /** P-256 曲线参数（a/b/p/阶等），用于公钥点压缩/解压。从标准库取，不硬编码常数。 */
    private val ecParameterSpec: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(EC_CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(EC_CURVE))
        }.generateKeyPair()

    /**
     * 压缩公钥编码（SEC1 压缩点，33 字节）：`[0x02|0x03] ‖ X(32B)`，前缀按 Y 坐标奇偶。
     * 需求要求二维码用「压缩格式公钥 33 字节」，替换原 X.509(SPKI ~91 字节)。
     */
    fun compressPublicKey(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return ByteArray(COMPRESSED_LEN).also { out ->
            out[0] = if (w.affineY.testBit(0)) 0x03 else 0x02
            val x = toFixed(w.affineX, FIELD_BYTES)
            System.arraycopy(x, 0, out, 1, FIELD_BYTES)
        }
    }

    /**
     * 解压公钥（33 字节压缩点 → `ECPublicKey`）：由 X 解 `y² = x³ + ax + b (mod p)`。
     * secp256r1 的 `p ≡ 3 (mod 4)`，故 `y = (rhs)^((p+1)/4) mod p`，再按前缀奇偶选 y 或 p−y。
     */
    fun decompressPublicKey(data: ByteArray): PublicKey {
        require(data.size == COMPRESSED_LEN) { "压缩公钥长度应为 $COMPRESSED_LEN 字节" }
        val prefix = data[0].toInt() and 0xFF
        require(prefix == 0x02 || prefix == 0x03) { "非法压缩公钥前缀：$prefix" }
        val params = ecParameterSpec
        val p = (params.curve.field as ECFieldFp).p
        val x = BigInteger(1, data.copyOfRange(1, COMPRESSED_LEN))
        // rhs = x³ + a·x + b (mod p)
        val rhs = x.modPow(BigInteger.valueOf(3), p)
            .add(params.curve.a.multiply(x))
            .add(params.curve.b)
            .mod(p)
        // p ≡ 3 (mod 4) → 平方根 = rhs^((p+1)/4) mod p
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        require(y.modPow(BigInteger.valueOf(2), p) == rhs) { "压缩公钥不在曲线上" }
        if (y.testBit(0) != (prefix == 0x03)) y = p.subtract(y)
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** BigInteger → 固定 [len] 字节大端（去掉 BigInteger 可能的符号前导 0、左侧补零）。 */
    private fun toFixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == len -> raw
            raw.size == len + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, raw.size)
            raw.size < len -> ByteArray(len - raw.size) + raw
            else -> raw.copyOfRange(raw.size - len, raw.size)
        }
    }

    /** ECDH 协商 + HKDF 派生 32 字节 AES 密钥。[peerCompressedPublicKey] = 对端压缩公钥（33 字节）。 */
    fun deriveSharedKey(privateKey: PrivateKey, peerCompressedPublicKey: ByteArray): ByteArray {
        val peerPublic = decompressPublicKey(peerCompressedPublicKey)
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
