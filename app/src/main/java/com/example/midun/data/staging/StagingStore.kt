package com.example.midun.data.staging

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.example.midun.data.real.RealFileSystem
import com.example.midun.media.CardFileDataSource
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天文件传输的暂存/缓存存储（`.recv_`/`.sent_`）。
 *
 * **唯一职责**：把收发文件的暂存 IO 收敛到安全卡隐藏区（[RealFileSystem]）。**仅管暂存缓存，不碰隐私文件夹**
 * （保存到文件夹另是卡内操作）。
 *
 * 历史：曾按 `TestModeManager` 在真卡 / 无卡本地暂存间切换（[[project_midun_nocard_testmode]]），2026 年砍掉
 * 无卡测试模式后收成真卡单路径，本类退化为对 [RealFileSystem] 的薄封装（保留供各仓库/网络层复用同一暂存语义）。
 */
@Singleton
class StagingStore @Inject constructor(
    private val real: RealFileSystem
) {
    // —— 写/接收原语（句柄 >0 有效）——
    fun create(path: String): Int = real.streamCreate(path)

    fun write(handle: Int, buf: ByteArray, off: Int, len: Int): Int =
        real.streamWrite(handle, buf, off, len)

    fun close(handle: Int) {
        real.streamClose(handle)
    }

    fun delete(path: String): Boolean = real.streamDelete(path)

    fun exists(path: String): Boolean = real.exists(path)

    fun sizeOrNull(path: String): Long? = real.fileSizeOrNull(path)

    /**
     * 流式写整文件（手机来源发送方副本 `.sent_`）。委托 [RealFileSystem.writeFile]（缓存路径非用户文件 →
     * 原始字节写、不叠 DEK）。[onProgress] 可抛异常以取消（半成品由调用方删）。
     */
    fun writeFile(path: String, input: InputStream, maxBytes: Long, onProgress: (Long) -> Unit): Result<Long> =
        real.writeFile(path, input, maxBytes, onProgress = onProgress)

    // —— 读原语（语音回放 / 图片预览 / 重发）——
    /** 打开暂存文件为输入流（调用方 use{}）。 */
    fun openRead(path: String): InputStream = real.openCardStream(path)

    /** 整读暂存文件字节（图片预览；不存在/读失败回 null）。 */
    fun readBytes(path: String): ByteArray? =
        runCatching { real.openCardStream(path).use { it.readBytes() } }.getOrNull()

    // —— 视频预览 media3 DataSource（按需随机读，不落整文件）——
    /** 暂存视频的 DataSource 工厂：走卡内流式解密读。 */
    @UnstableApi
    fun videoDataSourceFactory(path: String): DataSource.Factory = CardFileDataSource.Factory(real, path)

    /** 暂存视频喂给 ExoPlayer 的 MediaItem uri：卡内读用占位（路径在工厂里固定）。 */
    fun videoUri(path: String): Uri = Uri.parse("card://preview")
}
