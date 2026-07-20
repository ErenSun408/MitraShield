package com.example.midun.media

import android.media.MediaDataSource
import com.example.midun.crypto.FileCrypto
import com.example.midun.crypto.FileHeader
import com.example.midun.data.real.RealFileSystem

/**
 * `android.media.MediaDataSource`（API 23+，minSdk 24 满足），从安全卡隐藏区**随机读 + 流式解密**，供
 * [android.media.MediaMetadataRetriever] 提取视频首帧/时长而**不把视频解密落盘**。加密逻辑与
 * [CardFileDataSource] 同源（M12.3 分块 GCM：明文偏移 → 密文块 → 解块）。私藏文件夹视频均为加密文件
 * （叠 DEK）；未加密文件（无文件头 / DEK 缺失）回退原始字节直读。
 *
 * 线程：`RealFileSystem` 的 stream* 各自在 `fsShell` 上串行；本类另受 FileViewModel 的视频缩略图串行锁保护，
 * 故 seek+read 序列不会被其它句柄的操作穿插。
 */
class CardMediaDataSource(
    private val fs: RealFileSystem,
    path: String
) : MediaDataSource() {

    private var handle: Int = fs.streamOpen(path)

    private var encrypted = false
    private var dek: ByteArray = ByteArray(0)
    private var fileNonce: ByteArray = ByteArray(0)
    private var plaintextSize: Long = 0
    private var totalChunks: Int = 0
    private var rawSize: Long = 0

    // 已解密块缓存（retriever 常在同一块内多次小读，避免重复解密）。
    private var cachedChunk: Int = -1
    private var cachedPlain: ByteArray = ByteArray(0)

    init {
        if (handle > 0) {
            val d = fs.currentDek()
            val head = ByteArray(FileHeader.BYTES)
            val parsed = if (d != null && readFullAt(0, head)) FileHeader.parse(head) else null
            if (parsed != null) {
                encrypted = true
                dek = d!!
                fileNonce = parsed.fileNonce
                plaintextSize = parsed.plaintextSize
                totalChunks = ((plaintextSize + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            } else {
                encrypted = false
                rawSize = fs.streamSize(handle)
            }
        }
    }

    override fun getSize(): Long = if (encrypted) plaintextSize else rawSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (handle <= 0) return -1
        val total = getSize()
        if (position >= total) return -1
        if (!encrypted) {
            fs.streamSeek(handle, position)
            var got = 0
            while (got < size) {
                val n = fs.streamRead(handle, buffer, offset + got, size - got)
                if (n <= 0) break
                got += n
            }
            return if (got == 0) -1 else got
        }
        // 加密：按明文 position 跨块拼读。
        var pos = position
        var written = 0
        val end = minOf(position + size, plaintextSize)
        while (pos < end) {
            val plain = plainChunk((pos / CHUNK).toInt()) ?: break
            val within = (pos % CHUNK).toInt()
            if (within >= plain.size) break
            val n = minOf(plain.size - within, (end - pos).toInt())
            System.arraycopy(plain, within, buffer, offset + written, n)
            written += n
            pos += n
        }
        return if (written == 0) -1 else written
    }

    /** 载入并解密第 [index] 块明文（命中缓存直接返回）。越界/读不足回 null。 */
    private fun plainChunk(index: Int): ByteArray? {
        if (index == cachedChunk) return cachedPlain
        if (index < 0 || index >= totalChunks) return null
        val plainLen = minOf(CHUNK.toLong(), plaintextSize - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
        val cipherOffset = FileHeader.BYTES.toLong() + index.toLong() * (CHUNK.toLong() + FileCrypto.GCM_TAG_BYTES)
        val cipher = ByteArray(plainLen + FileCrypto.GCM_TAG_BYTES)
        if (!readFullAt(cipherOffset, cipher)) return null
        val isLast = index == totalChunks - 1
        cachedPlain = FileCrypto.decryptChunk(dek, fileNonce, index, isLast, cipher)
        cachedChunk = index
        return cachedPlain
    }

    /** 从卡内绝对偏移 [offset] 精确读满 [buf]（SFRead 可能短读）。读满回 true。 */
    private fun readFullAt(offset: Long, buf: ByteArray): Boolean {
        fs.streamSeek(handle, offset)
        var off = 0
        while (off < buf.size) {
            val n = fs.streamRead(handle, buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    override fun close() {
        if (handle > 0) {
            fs.streamClose(handle)
            handle = -1
        }
    }

    private companion object {
        const val CHUNK = FileCrypto.CHUNK_PLAIN_BYTES
    }
}
