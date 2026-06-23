package com.example.midun.crypto

/**
 * 加密用户文件的卡内文件头（M12.2）。落卡布局：
 *
 * ```
 * [ header(21B) ] [ encChunk0 ] [ encChunk1 ] ... [ encChunkN ]
 *   header = MAGIC(4) ‖ VERSION(1) ‖ plaintextSize(8B 大端) ‖ fileNonce(8B)
 *   encChunkI = FileCrypto.encryptChunk(DEK, fileNonce, i, isLast, 明文块)  // 每块 +16B GCM tag
 * ```
 *
 * - **plaintextSize** 存明文字节数：`SFGetSize` 只知密文大小，UI 列表 / 进度 / 预览 seek 都要明文大小，
 *   故写进文件头。
 * - **fileNonce** 是每文件一份的随机前缀，与 chunkIndex 拼成每块 GCM nonce（见 [FileCrypto]）。
 * - **MAGIC** 兼作「此文件是否 App 层加密」的判别：读路径（M12.3）/大小记账据 MAGIC 决定解密还是按原始
 *   字节处理（兼容未加密的旧卡文件）。
 *
 * 纯字节运算、无 Android 依赖 → JVM 可测（见 `FileHeaderTest`）。
 */
object FileHeader {

    /** "MDF1" = MiDun File v1。 */
    val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    const val VERSION: Byte = 1

    private const val SIZE_OFFSET = 5 // MAGIC(4) + VERSION(1)
    private const val NONCE_OFFSET = 13 // SIZE_OFFSET + 8
    /** 文件头总长度（21 字节）。 */
    const val BYTES = NONCE_OFFSET + FileCrypto.FILE_NONCE_BYTES

    /** 解析结果：明文字节数 + 文件 nonce。 */
    class Parsed(val plaintextSize: Long, val fileNonce: ByteArray)

    /** 构造文件头字节。[fileNonce] 须为 [FileCrypto.FILE_NONCE_BYTES] 字节。 */
    fun build(plaintextSize: Long, fileNonce: ByteArray): ByteArray {
        require(plaintextSize >= 0) { "明文大小不能为负" }
        require(fileNonce.size == FileCrypto.FILE_NONCE_BYTES) { "fileNonce 应为 ${FileCrypto.FILE_NONCE_BYTES} 字节" }
        return ByteArray(BYTES).also { h ->
            System.arraycopy(MAGIC, 0, h, 0, MAGIC.size)
            h[MAGIC.size] = VERSION
            putLongBE(h, SIZE_OFFSET, plaintextSize)
            System.arraycopy(fileNonce, 0, h, NONCE_OFFSET, FileCrypto.FILE_NONCE_BYTES)
        }
    }

    /** 解析文件头；MAGIC/版本不符或长度不足返回 null（视作未加密/旧文件）。 */
    fun parse(header: ByteArray): Parsed? {
        if (header.size < BYTES) return null
        if (!MAGIC.indices.all { header[it] == MAGIC[it] } || header[MAGIC.size] != VERSION) return null
        val size = getLongBE(header, SIZE_OFFSET)
        if (size < 0) return null
        return Parsed(size, header.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + FileCrypto.FILE_NONCE_BYTES))
    }

    /** 密文文件大小 → 明文文件大小（含头 + 每块 GCM tag 的反推），用于校验/估算。 */
    fun cipherToPlaintextSize(cipherSize: Long, plaintextSize: Long): Boolean {
        // 块数按明文大小算（与写入端一致），每块 +16B tag；空文件也有 1 块（纯 tag）。
        val chunks = ((plaintextSize + FileCrypto.CHUNK_PLAIN_BYTES - 1) / FileCrypto.CHUNK_PLAIN_BYTES)
            .coerceAtLeast(1)
        return cipherSize == BYTES + plaintextSize + chunks * FileCrypto.GCM_TAG_BYTES
    }

    private fun putLongBE(out: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) out[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    private fun getLongBE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
