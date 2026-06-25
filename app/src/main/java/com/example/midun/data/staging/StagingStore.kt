package com.example.midun.data.staging

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import com.example.midun.data.TestModeManager
import com.example.midun.data.real.RealFileSystem
import com.example.midun.media.CardFileDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天文件传输的暂存/缓存存储（`.recv_`/`.sent_`），[[project_midun_nocard_testmode]] T2 抽象层。
 *
 * **唯一职责**：把收发文件的暂存 IO 从「硬绑安全卡」解耦——真卡模式走卡隐藏区（[RealFileSystem]，行为
 * 逐字不变），[TestModeManager] 测试模式（无卡）走手机本地存储（[LocalStagingStore]），让没卡的客户也能
 * 真正收发文件/语音。**仅管暂存缓存，不碰隐私文件夹**（保存到文件夹仍是卡内操作、测试模式 UI 禁用）。
 *
 * 选择按**每次调用**读 [TestModeManager.isTestMode]（而非 DI 期固定实现）：测试模式可能在 App 启动后才
 * 由用户点「无卡测试」开启，单例若在那之前已被注入就会拿错实现。句柄用 [LOCAL_HANDLE_BASE] 命名空间区分
 * 两端，故 create 返回的句柄在后续 write/close 必回到同一后端，即便中途切模式也不串。
 */
@Singleton
class StagingStore @Inject constructor(
    private val testMode: TestModeManager,
    private val real: RealFileSystem,
    @ApplicationContext context: android.content.Context
) {
    private val local = LocalStagingStore(context)

    /** 本次调用是否走本地（无卡测试模式）。 */
    private fun useLocal(): Boolean = testMode.isTestMode.value

    /** 该句柄属本地后端（命名空间区分，见类注释）。 */
    private fun isLocalHandle(handle: Int): Boolean = handle >= LOCAL_HANDLE_BASE

    // —— 写/接收原语（句柄 >0 有效；本地句柄 ≥ LOCAL_HANDLE_BASE）——
    fun create(path: String): Int = if (useLocal()) local.create(path) else real.streamCreate(path)

    fun write(handle: Int, buf: ByteArray, off: Int, len: Int): Int =
        if (isLocalHandle(handle)) local.write(handle, buf, off, len) else real.streamWrite(handle, buf, off, len)

    fun close(handle: Int) {
        if (isLocalHandle(handle)) local.close(handle) else real.streamClose(handle)
    }

    fun delete(path: String): Boolean = if (useLocal()) local.delete(path) else real.streamDelete(path)

    fun exists(path: String): Boolean = if (useLocal()) local.exists(path) else real.exists(path)

    fun sizeOrNull(path: String): Long? = if (useLocal()) local.sizeOrNull(path) else real.fileSizeOrNull(path)

    /**
     * 流式写整文件（手机来源发送方副本 `.sent_`）。真卡委托 [RealFileSystem.writeFile]（缓存路径非用户文件 →
     * 原始字节写、不叠 DEK）；本地写磁盘。[onProgress] 可抛异常以取消（半成品由调用方删）。
     */
    fun writeFile(path: String, input: InputStream, maxBytes: Long, onProgress: (Long) -> Unit): Result<Long> =
        if (useLocal()) local.writeFile(path, input, maxBytes, onProgress)
        else real.writeFile(path, input, maxBytes, onProgress = onProgress)

    // —— 读原语（语音回放 / 图片预览 / 重发）——
    /** 打开暂存文件为输入流（调用方 use{}）。 */
    fun openRead(path: String): InputStream = if (useLocal()) local.openRead(path) else real.openCardStream(path)

    /** 整读暂存文件字节（图片预览；不存在/读失败回 null）。 */
    fun readBytes(path: String): ByteArray? =
        if (useLocal()) local.readBytes(path)
        else runCatching { real.openCardStream(path).use { it.readBytes() } }.getOrNull()

    // —— 视频预览 media3 DataSource（按需随机读，不落整文件）——
    /** 暂存视频的 DataSource 工厂：真卡走卡内流式解密读，本地走文件直读。 */
    @UnstableApi
    fun videoDataSourceFactory(path: String): DataSource.Factory =
        if (useLocal()) FileDataSource.Factory() else CardFileDataSource.Factory(real, path)

    /** 暂存视频喂给 ExoPlayer 的 MediaItem uri：真卡用占位（路径在工厂里固定），本地用真实 file:// uri。 */
    fun videoUri(path: String): Uri =
        if (useLocal()) Uri.fromFile(local.fileFor(path)) else Uri.parse("card://preview")

    companion object {
        /** 本地句柄命名空间基址（远超 FSShell 的小整数句柄，二者不会撞）。 */
        const val LOCAL_HANDLE_BASE = 0x4000_0000
    }
}

/**
 * 无卡测试模式的本地暂存实现（手机私有目录 `filesDir/nocard_staging/`）。把卡内逻辑路径（如 `0:/.recv_<id>`）
 * 映射为本地文件（取末段文件名 `.recv_<id>`，全局唯一）。仅测试模式用——明文落手机本地，无卡保密能力，UI 已声明。
 */
class LocalStagingStore(context: android.content.Context) {

    private val dir: File = File(context.filesDir, "nocard_staging").apply { mkdirs() }
    private val handles = ConcurrentHashMap<Int, RandomAccessFile>()
    private val nextId = AtomicInteger(StagingStore.LOCAL_HANDLE_BASE)

    /** 卡内逻辑路径 → 本地文件（取末段名，`.recv_<id>`/`.sent_<id>` 全局唯一，不会冲突）。 */
    fun fileFor(path: String): File = File(dir, path.substringAfterLast('/'))

    fun create(path: String): Int {
        val raf = RandomAccessFile(fileFor(path), "rw").apply { setLength(0) }
        val id = nextId.incrementAndGet()
        handles[id] = raf
        return id
    }

    fun write(handle: Int, buf: ByteArray, off: Int, len: Int): Int {
        val raf = handles[handle] ?: return -1
        raf.write(buf, off, len)
        return len
    }

    fun close(handle: Int) {
        runCatching { handles.remove(handle)?.close() }
    }

    fun delete(path: String): Boolean = fileFor(path).delete()

    fun exists(path: String): Boolean = fileFor(path).exists()

    fun sizeOrNull(path: String): Long? = fileFor(path).takeIf { it.exists() }?.length()

    fun openRead(path: String): InputStream {
        val f = fileFor(path)
        if (!f.exists()) throw IOException("暂存文件不存在：$path")
        return FileInputStream(f)
    }

    fun readBytes(path: String): ByteArray? = fileFor(path).takeIf { it.exists() }?.readBytes()

    fun writeFile(path: String, input: InputStream, maxBytes: Long, onProgress: (Long) -> Unit): Result<Long> {
        val f = fileFor(path)
        return runCatching {
            var total = 0L
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("文件超过上限 ${maxBytes / (1024 * 1024)}MB")
                    if (n > 0) out.write(buf, 0, n)
                    onProgress(total) // 可抛异常取消
                }
            }
            total
        }.onFailure { runCatching { f.delete() } } // 失败/取消删半成品
    }
}
