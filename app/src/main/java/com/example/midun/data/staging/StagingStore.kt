package com.example.midun.data.staging

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.example.midun.data.local.LocalFileSystem
import com.example.midun.media.VaultFileDataSource
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天文件传输的暂存/缓存存储（`.recv_`/`.sent_`）。无卡版直接落本地隐私库 [LocalFileSystem] 的 vault 根级
 * **隐藏文件**（`.` 前缀 → 不加密、列表隐藏），与隐私文件夹同一文件系统——保存到文件夹的
 * [LocalFileSystem.copyWithinCard] 等操作无需跨区，收发/预览逻辑与真卡逐字一致。
 *
 * **仅管暂存缓存，不碰隐私文件夹**。历史：真卡版在卡隐藏区暂存；曾有 `TestModeManager` 无卡测试分支走手机
 * 本地存储——bobo-nocard 收成本地单路径，删掉双后端与句柄命名空间区分。
 */
@Singleton
class StagingStore @Inject constructor(
    private val fs: LocalFileSystem
) {
    // —— 写/接收原语（句柄 >0 有效）——
    fun create(path: String): Int = fs.streamCreate(path)
    fun write(handle: Int, buf: ByteArray, off: Int, len: Int): Int = fs.streamWrite(handle, buf, off, len)
    fun close(handle: Int) = fs.streamClose(handle)
    fun delete(path: String): Boolean = fs.streamDelete(path)
    fun exists(path: String): Boolean = fs.exists(path)
    fun sizeOrNull(path: String): Long? = fs.fileSizeOrNull(path)

    /** 流式写整文件（手机来源发送方副本 `.sent_`）。缓存路径非用户文件 → 原始字节写、不叠 DEK。 */
    fun writeFile(path: String, input: InputStream, maxBytes: Long, onProgress: (Long) -> Unit): Result<Long> =
        fs.writeFile(path, input, maxBytes, onProgress = onProgress)

    // —— 读原语（语音回放 / 图片预览 / 重发）——
    /** 打开暂存文件为输入流（调用方 use{}）。 */
    fun openRead(path: String): InputStream = fs.openCardStream(path)

    /** 整读暂存文件字节（图片预览；不存在/读失败回 null）。 */
    fun readBytes(path: String): ByteArray? =
        runCatching { fs.openCardStream(path).use { it.readBytes() } }.getOrNull()

    // —— 视频预览 media3 DataSource（按需随机读，不落整文件）——
    /** 暂存视频的 DataSource 工厂：本地隐私库流式读（`.recv_`/`.sent_` 未加密 → 原始字节直读）。 */
    @UnstableApi
    fun videoDataSourceFactory(path: String): DataSource.Factory = VaultFileDataSource.Factory(fs, path)

    /** 喂给 ExoPlayer 的 MediaItem uri：路径在工厂里固定，用占位。 */
    fun videoUri(path: String): Uri = Uri.parse("card://preview")
}
