package com.example.midun.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.midun.crypto.FileCrypto
import com.example.midun.crypto.FileHeader
import com.example.midun.data.real.RealFileSystem
import java.io.IOException

/**
 * media3 DataSource，从安全卡隐藏区**流式解密**读取（方案 B，视频预览）。ExoPlayer 按需向卡要字节
 * （`SFOpen`/`SFSeek64`/`SFRead`），**不把整段视频解密落盘**——秒开、可拖动、明文不离卡。仅真卡模式可用。
 *
 * **M12.3 加密随机读**：加密用户文件（文件头 MAGIC 命中）按「明文偏移 → 密文块 → 解块」映射服务任意 seek——
 * 明文位置 `p` 落在第 `p/64KB` 块，块的卡内密文偏移 = `头长 + 块号·(64KB+tag)`，seek 到该处读一块密文解密、
 * 从块内 `p%64KB` 起供字节。未加密的缓存视频（`.recv_`/`.sent_`，Option-1 不叠 DEK）走原始字节直读。
 */
@UnstableApi
class CardFileDataSource(
    private val fs: RealFileSystem,
    private val path: String
) : BaseDataSource(/* isNetwork = */ false) {

    private var handle: Int = -1
    private var bytesRemaining: Long = 0
    private var dataUri: Uri? = null

    // —— 加密态（M12.3）；encrypted=false 时回退原始字节直读 ——
    private var encrypted = false
    private var dek: ByteArray = ByteArray(0)
    private var fileNonce: ByteArray = ByteArray(0)
    private var plaintextSize: Long = 0
    private var totalChunks: Int = 0
    private var curChunk: Int = -1
    private var curPlain: ByteArray = ByteArray(0)
    private var curPlainPos: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        dataUri = dataSpec.uri
        transferInitializing(dataSpec)
        handle = fs.streamOpen(path)
        if (handle <= 0) throw IOException("打开卡内文件失败：$path")

        val d = fs.currentDek()
        val head = ByteArray(FileHeader.BYTES)
        val parsed = if (d != null && readFull(head)) FileHeader.parse(head) else null
        if (parsed != null) {
            encrypted = true
            dek = d!!
            fileNonce = parsed.fileNonce
            plaintextSize = parsed.plaintextSize
            totalChunks = ((plaintextSize + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            seekToPlaintext(dataSpec.position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (plaintextSize - dataSpec.position).coerceAtLeast(0)
        } else {
            encrypted = false
            fs.streamSeek(handle, 0) // 可能已读了头 → 回卷
            val size = fs.streamSize(handle)
            if (dataSpec.position > 0) fs.streamSeek(handle, dataSpec.position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (size - dataSpec.position).coerceAtLeast(0)
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (!encrypted) {
            val toRead = minOf(length.toLong(), bytesRemaining).toInt()
            val n = fs.streamRead(handle, buffer, offset, toRead)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bytesRemaining -= n
            bytesTransferred(n)
            return n
        }
        // 加密：当前块耗尽则载入下一块。
        if (curPlainPos >= curPlain.size) {
            val next = curChunk + 1
            if (next >= totalChunks) return C.RESULT_END_OF_INPUT
            loadChunk(next)
        }
        val avail = curPlain.size - curPlainPos
        val n = minOf(minOf(length.toLong(), avail.toLong()), bytesRemaining).toInt()
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(curPlain, curPlainPos, buffer, offset, n)
        curPlainPos += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /** 定位到明文偏移 [pos]：载入其所在块，块内偏移设为 `pos % 64KB`。 */
    private fun seekToPlaintext(pos: Long) {
        loadChunk((pos / CHUNK).toInt())
        curPlainPos = (pos % CHUNK).toInt().coerceAtMost(curPlain.size)
    }

    /** 载入第 [index] 块密文并解密为 [curPlain]。 */
    private fun loadChunk(index: Int) {
        val plainLen = minOf(CHUNK.toLong(), plaintextSize - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
        val cipherOffset = FileHeader.BYTES.toLong() + index.toLong() * (CHUNK.toLong() + FileCrypto.GCM_TAG_BYTES)
        fs.streamSeek(handle, cipherOffset)
        val cipher = ByteArray(plainLen + FileCrypto.GCM_TAG_BYTES)
        if (!readFull(cipher)) throw IOException("密文不足 @chunk$index")
        val isLast = index == totalChunks - 1
        curPlain = FileCrypto.decryptChunk(dek, fileNonce, index, isLast, cipher)
        curPlainPos = 0
        curChunk = index
    }

    /** 从卡句柄精确读满 [buf]（SFRead 可能短读，0/负即停）。读满回 true。 */
    private fun readFull(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = fs.streamRead(handle, buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    override fun getUri(): Uri? = dataUri

    override fun close() {
        if (handle > 0) {
            fs.streamClose(handle)
            handle = -1
        }
        transferEnded()
    }

    /** 工厂：固定一个卡内路径，供 ExoPlayer 的 MediaSource 用。 */
    @UnstableApi
    class Factory(private val fs: RealFileSystem, private val path: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = CardFileDataSource(fs, path)
    }

    private companion object {
        const val CHUNK = FileCrypto.CHUNK_PLAIN_BYTES
    }
}
