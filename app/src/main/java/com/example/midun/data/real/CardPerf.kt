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

/**
 * 现场诊断日志：**专注于「聊天会话为什么断了」**（2026-07-30 收窄；此前是鸿蒙 mate40 登录慢的卡层计时工具，
 * 客户升级系统后慢的问题消失，各调用点的 `time()` 包裹已整体撤除）。
 *
 * 记什么：
 * - `[net]`：会话建立/断开及其**原因**（心跳判死、PING 写失败、读循环怎么结束的、卡掉认证态、主动拆会话）、
 *   文件通道起止、收发文件起止——见 `P2PSessionManager`；
 * - `[diag]`：进程启动，以及**上次进程是怎么退出的**——见 `ProcessExitLog`；
 * - 少量卡层**错误码**记录（开盘失败 `ret=`），不是耗时，留着是因为「登录进不去」那条线还没在真机上定案。
 *
 * **为什么这份日志能定案**：断连只有两种可能——App 自己断的（日志里必有一行 `[net]` 说明原因），或者进程被
 * 打死了（App 一行都写不出，但下次启动时 `[diag] 上次退出` 会点名原因）。两者在日志里长得完全不一样。
 *
 * **当前整体关闭，见 [ENABLED]**；以下描述的是打开时的行为。
 *
 * 写在手机「下载」目录的文本文件里，客户不连电脑也能在文件管理器找到、直接发回来。
 * 文件位置：`下载/midun_perf_<启动时刻>.log`（每次进程启动一份，避免覆盖上一轮）。每条即时整表重写并刷盘，
 * 保证进程被杀也不丢已写的行——这正是排查崩溃时最需要的性质。
 */
object CardPerf {
    /**
     * **总开关，当前关闭**（2026-08-11）。关掉的是**输出**：[mark] 直接返回，既不写「下载」目录也不打
     * logcat；43 处调用点、写盘逻辑、文件命名规则全部原样留着，改回 `true` 即恢复，无需改任何调用方。
     *
     * 为什么留着而不是删掉：断连/登录那两条线都还没在真机上彻底定案，下次现场复现时这份日志仍是唯一能
     * 分清「App 自己断的」与「进程被打死」的证据。为什么默认关：它往手机「下载」目录写**明文**文本，
     * 里面有会话起止、设备行为轨迹与卡层错误码——安全产品不该在客户日常使用时留这么一份东西。
     */
    private const val ENABLED = false

    private const val TAG = "MiDunPerf"
    private const val FILE_PREFIX = "midun_perf_"

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val lines = StringBuilder()

    @Volatile private var appContext: Context? = null
    @Volatile private var fileUri: Uri? = null // Android 10+ MediaStore 目标（Q 以下走直接文件）

    /** 接线 Application Context（供写「下载」目录）。多次调用只取第一次的 applicationContext。 */
    fun attach(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    /**
     * 记一条事件（带时刻）。同时打一份到 logcat，方便能连电脑时直接看。
     *
     * [ENABLED] 为假时整条是空操作——**连文件都不会被创建**（首次刷盘才 insert MediaStore 记录），
     * 客户的「下载」目录里不会多出任何东西。
     */
    fun mark(label: String) {
        if (!ENABLED) return
        record("· $label")
        Log.i(TAG, label)
    }

    /** 追加一行并即时刷盘。写的是手机「下载」目录，不碰卡的 fsShell 锁。 */
    @Synchronized
    private fun record(line: String) {
        lines.append(now()).append("  ").append(line).append('\n')
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
