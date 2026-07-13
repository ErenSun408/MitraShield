package com.example.midun.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App 层「静态文件加密」原语（M12.1）。纯 `javax.crypto`/`java.security`，**无 Android 依赖**，可在 JVM
 * 单测直接运行（见 `FileCryptoTest`）。与 [com.example.midun.network.P2PCrypto] 是孪生关系：P2PCrypto 管
 * 「传输中」的 P2P 会话密钥，本类管「落卡静止」的文件数据密钥（DEK）。
 *
 * 两类能力：
 * - **密钥包裹**（[wrapKey]/[unwrapKey]）：用 KEK 对 DEK 做 AES-256-GCM 加密/解密，供 keystore 存
 *   `wrappedDEK`、「密钥更新」重包（DEK 不变、文件不丢）。
 * - **分块文件加解密**（[encryptChunk]/[decryptChunk]）：与 P2PCrypto 同一「确定性 nonce + AAD」方案——
 *   `nonce = fileNonce(8B) ‖ chunkIndex(4B)`、`AAD = fileNonce ‖ chunkIndex ‖ isLast`，防块重排/重放/截断。
 *   供 M12.2/12.3 在 RealFileSystem 读写路径插入透明加解密。
 *
 * **诚实定位**：卡 + 密码才是真正的保密门（卡硬件 AES）；本层 DEK 只为「控制/功能」（加密导出、可重置
 * 的封装密钥），不增加保密性。详见 M12 设计。
 */
object FileCrypto {

    const val KEY_BYTES = 32 // AES-256（DEK / KEK 均 32 字节）
    const val FILE_NONCE_BYTES = 8 // 每文件一份随机前缀，与 chunkIndex 拼成每块 GCM nonce
    /**
     * 落卡加密分块大小（**单一来源**：RealFileSystem.CHUNK / VaultFileDataSource / FileHeader 全取此值）。
     * 取 16KB：视频拖拽时任意一次随机读只需读+解一整块（16KB），比 64KB 少读 3/4 数据 → seek 更快；每块多
     * [GCM_TAG_BYTES] 的 tag（16KB 块开销约 0.1%，可忽略）。**改此值会改变落卡格式**，旧加密文件需重导。
     */
    const val CHUNK_PLAIN_BYTES = 16 * 1024
    /** GCM 认证标签字节数（每块密文 = 明文 + 此）。读路径据此算每块密文长度。 */
    const val GCM_TAG_BYTES = 16

    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val rng = SecureRandom()

    /** 生成随机 AES-256 密钥（DEK 或 KEK）。 */
    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also { rng.nextBytes(it) }

    /** 生成每文件一份的随机 8 字节 fileNonce。 */
    fun newFileNonce(): ByteArray = ByteArray(FILE_NONCE_BYTES).also { rng.nextBytes(it) }

    /** 用 [kek] 包裹 [dek]（AES-256-GCM）。输出 = IV(12B) ‖ 密文+tag（32+16=48B）→ 共 60B。 */
    fun wrapKey(kek: ByteArray, dek: ByteArray): ByteArray = gcmEncrypt(kek, dek)

    /** 用 [kek] 解包 [wrapped]（[wrapKey] 的逆）；KEK 不符/被篡改抛 `AEADBadTagException`。 */
    fun unwrapKey(kek: ByteArray, wrapped: ByteArray): ByteArray = gcmDecrypt(kek, wrapped)

    /**
     * 加密一个文件块。[plain] 为本块明文（末块可不足 64KB），[chunkIndex] 从 0 递增，[isLast] 标记末块。
     * 返回 GCM 密文+tag（不含 IV）。同一 ([key],[fileNonce]) 下各 [chunkIndex] 必须唯一。
     */
    fun encryptChunk(
        key: ByteArray, fileNonce: ByteArray, chunkIndex: Int, isLast: Boolean, plain: ByteArray
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, chunkIv(fileNonce, chunkIndex)))
        updateAAD(chunkAad(fileNonce, chunkIndex, isLast))
        doFinal(plain)
    }

    /**
     * 解密一个文件块。[cipherText] = [encryptChunk] 输出。[fileNonce]/[chunkIndex]/[isLast] 必须与加密端一致，
     * 否则（块被重排/重放/截断/篡改）GCM 校验失败抛 `AEADBadTagException`。
     */
    fun decryptChunk(
        key: ByteArray, fileNonce: ByteArray, chunkIndex: Int, isLast: Boolean, cipherText: ByteArray
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, chunkIv(fileNonce, chunkIndex)))
        updateAAD(chunkAad(fileNonce, chunkIndex, isLast))
        doFinal(cipherText)
    }

    // —— 内部：GCM 字节加解密（密钥包裹用，随机 IV，输出 IV ‖ 密文+tag）——

    private fun gcmEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plain)
    }

    private fun gcmDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > GCM_IV_BYTES) { "密文长度不足" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(data.copyOfRange(GCM_IV_BYTES, data.size))
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
}
