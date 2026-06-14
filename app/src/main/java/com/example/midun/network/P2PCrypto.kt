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
 * 提前实现真加密，M10 端到端真加密（密钥来自软件）。**M11 审计确认 FSShell SDK 不提供通信密钥/
 * ECDH 接口（安全层只管卡内存储加密、Android JNI 未暴露），故软件 ECDH+AES-GCM 即为最终形态**；
 * M11 仅把 P2P deviceSn 换成真实卡 SN（SFDiskGetSN），不动加密本身。
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

    // —— 分块加密（M11.5.3 文件传输）——
    // 文件不能像短消息那样每块塞随机 IV（+12B/块膨胀、且 IV 要跟着传）。改用「确定性 nonce + AAD」：
    //   nonce = fileNonce(8B 随机，每文件一份) ‖ chunkIndex(4B 大端) —— 同密钥下唯一（不同文件 fileNonce 不同、
    //           同文件 chunkIndex 递增），满足 GCM nonce 不可复用。
    //   AAD   = fileNonce(8B) ‖ chunkIndex(4B) ‖ isLast(1B) —— 把「这是第几块/是不是最后一块/属于哪个文件」
    //           绑进认证标签，防止攻击者重排块、重放块、或截断（丢最后一块伪装文件结束）。
    // 输出仅 GCM 密文+tag（不含 IV，收端用同样的 fileNonce+chunkIndex 复原 nonce）。

    const val FILE_NONCE_BYTES = 8

    /** 生成每文件一份的随机 8 字节 fileNonce（与 chunkIndex 拼成每块的 GCM nonce）。 */
    fun newFileNonce(): ByteArray = ByteArray(FILE_NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * 加密一个文件块。[plain] 为本块明文（最后一块可不足 64KB），[chunkIndex] 从 0 递增，[isLast] 标记末块。
     * 返回 GCM 密文+tag（不含 IV）。同一 ([key],[fileNonce]) 下各 [chunkIndex] 必须唯一。
     */
    fun encryptChunk(
        key: ByteArray, fileNonce: ByteArray, chunkIndex: Int, isLast: Boolean, plain: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, chunkIv(fileNonce, chunkIndex)))
            updateAAD(chunkAad(fileNonce, chunkIndex, isLast))
        }
        return cipher.doFinal(plain)
    }

    /**
     * 解密一个文件块。[cipherText] = [encryptChunk] 输出。[fileNonce]/[chunkIndex]/[isLast] 必须与加密端一致，
     * 否则（块被重排/重放/截断/篡改）GCM 校验失败抛 `AEADBadTagException`。
     */
    fun decryptChunk(
        key: ByteArray, fileNonce: ByteArray, chunkIndex: Int, isLast: Boolean, cipherText: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, chunkIv(fileNonce, chunkIndex)))
            updateAAD(chunkAad(fileNonce, chunkIndex, isLast))
        }
        return cipher.doFinal(cipherText)
    }

    /** 每块 GCM nonce = fileNonce(8B) ‖ chunkIndex(4B 大端)。 */
    private fun chunkIv(fileNonce: ByteArray, chunkIndex: Int): ByteArray {
        require(fileNonce.size == FILE_NONCE_BYTES) { "fileNonce 应为 $FILE_NONCE_BYTES 字节" }
        return ByteArray(GCM_IV_BYTES).also { iv ->
            System.arraycopy(fileNonce, 0, iv, 0, FILE_NONCE_BYTES)
            putIntBE(iv, FILE_NONCE_BYTES, chunkIndex)
        }
    }

    /** 每块 AAD = fileNonce(8B) ‖ chunkIndex(4B 大端) ‖ isLast(1B)。 */
    private fun chunkAad(fileNonce: ByteArray, chunkIndex: Int, isLast: Boolean): ByteArray =
        ByteArray(FILE_NONCE_BYTES + 4 + 1).also { aad ->
            System.arraycopy(fileNonce, 0, aad, 0, FILE_NONCE_BYTES)
            putIntBE(aad, FILE_NONCE_BYTES, chunkIndex)
            aad[FILE_NONCE_BYTES + 4] = if (isLast) 1 else 0
        }

    private fun putIntBE(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
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
