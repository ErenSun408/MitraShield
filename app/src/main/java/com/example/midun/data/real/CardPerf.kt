package com.example.midun.data.real

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 卡层耗时诊断（鸿蒙 mate40 登录/进主界面慢的定位工具）。
 *
 * 卡操作全走同一把 `synchronized(fsShell)` 全局锁、串行执行，鸿蒙 2.0.0 上底层 libusb 传输若慢/重试会被
 * 逐调用放大且互相排队叠加成分钟级。源码层只能排嫌疑、无法定罪——本工具给每个关键 native 调用打「起止 +
 * 毫秒」日志，**同时写到手机「下载」目录的文本文件**，客户不连电脑也能在文件管理器里找到、发回来分析。
 * 定位后整体移除（连带各调用点的 CardPerf.time 包裹）。
 *
 * 用法：进程首次用卡时 [attach] 一个 Context（[RealUsbManager] init 已接线）；再
 * `CardPerf.time("SFOpenDiskEx(auth)") { fsShell.SFOpenDiskEx(dn, hash) }` 计时。
 * 超过 [SLOW_MS] 的调用额外打一条 `SLOW`，在噪声里一眼挑出瓶颈。
 *
 * 文件位置：`下载/midun_perf_<启动时刻>.log`（每次进程启动一份，避免覆盖上一轮）。每个节点整表重写，
 * 一次登录只有几十行，重写开销可忽略。
 */
object CardPerf {
    private const val TAG = "MiDunPerf"
    private const val SLOW_MS = 1_000L
    private const val FILE_PREFIX = "midun_perf_"

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val lines = StringBuilder()

    /** 实时耗时文本，供登录页驱动模式面板显示（测试人员切模式后直接看数字，不必翻「下载」目录 log）。 */
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var fileUri: Uri? = null // Android 10+ MediaStore 目标（Q 以下走直接文件）

    /** 接线 Application Context（供写「下载」目录）。多次调用只取第一次的 applicationContext。 */
    fun attach(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    /** 计时执行 [block]，返回其结果；耗时记入日志（慢调用额外标 SLOW）。异常也计时并原样抛出。 */
    inline fun <T> time(label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            val result = block()
            log(label, start, ok = true)
            return result
        } catch (e: Throwable) {
            log("$label!ERR(${e.javaClass.simpleName})", start, ok = false)
            throw e
        }
    }

    /** 记一条即时事件点（无耗时，仅标时间线，如「AUTHENTICATED 已发布」）。 */
    fun mark(label: String) {
        record("· $label")
    }

    fun log(label: String, startMs: Long, ok: Boolean) {
        val ms = System.currentTimeMillis() - startMs
        record("${if (ok) "" else "✗ "}$label = ${ms}ms" + if (ms >= SLOW_MS) "   <<< SLOW" else "")
        Log.i(TAG, "$label = ${ms}ms")
    }

    /**
     * 追加一行并即时刷盘。写的是手机「下载」目录、不碰卡的 fsShell 锁 → 不会污染被测调用的耗时；
     * 每行都落盘保证 App 中途被杀也不丢日志。
     */
    @Synchronized
    private fun record(line: String) {
        lines.append(now()).append("  ").append(line).append('\n')
        _log.value = lines.toString()
        flush()
    }

    /** 把当前缓冲整表写入「下载」目录文件。失败静默（诊断工具不该影响主流程）。 */
    private fun flush() {
        val ctx = appContext ?: return
        val text = lines.toString().toByteArray()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val uri = fileUri ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "$FILE_PREFIX$stamp.log")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?.also { fileUri = it } ?: return
                }
                // "wt" = 截断重写（每次刷盘写全量缓冲，得到最新完整快照）。
                resolver.openOutputStream(uri, "wt")?.use { it.write(text) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, "$FILE_PREFIX$stamp.log").writeBytes(text)
            }
        }
    }

    private fun now(): String = timeFmt.format(Date())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
