package com.example.midun.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.midun.data.real.RealFileSystem
import java.io.IOException

/**
 * media3 DataSource，从安全卡隐藏区**流式解密**读取（方案 B，视频预览）。ExoPlayer 按需向卡要字节
 * （`SFOpen`/`SFSeek64`/`SFRead`），**不把整段视频解密落盘**——秒开、可拖动、明文不离卡。仅真卡模式可用。
 */
@UnstableApi
class CardFileDataSource(
    private val fs: RealFileSystem,
    private val path: String
) : BaseDataSource(/* isNetwork = */ false) {

    private var handle: Int = -1
    private var bytesRemaining: Long = 0
    private var dataUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        dataUri = dataSpec.uri
        transferInitializing(dataSpec)
        handle = fs.streamOpen(path)
        if (handle <= 0) throw IOException("打开卡内文件失败：$path")
        val size = fs.streamSize(handle)
        if (dataSpec.position > 0) fs.streamSeek(handle, dataSpec.position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (size - dataSpec.position).coerceAtLeast(0)
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = fs.streamRead(handle, buffer, offset, toRead)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
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
}
