package com.example.midun.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.example.midun.data.real.CardPerf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动时把**上一次进程是怎么结束的**写进诊断日志（`[diag]` 2026-07-30）。
 *
 * 为什么需要：客户报「会话连接断开」时，我们分不清两种完全不同的情况——
 * ① App 自己的断连逻辑触发了（心跳判死 / 卡掉认证态 / 对端关闭），日志里会留下 `[net]` 断连原因；
 * ② **进程直接没了**（原生崩溃、ANR、被系统杀），这时 App 一行日志都来不及写，对端只看到 socket 关闭。
 *
 * 第 ② 种从 App 内部无法自证，但系统替我们记着：[ActivityManager.getHistoricalProcessExitReasons]
 * （API 30+）能在**下次启动时**回答上次为什么退出。于是只要客户在断连后重开 App，日志里就会出现这一行，
 * 不需要连电脑抓 logcat。
 *
 * 注意我们自己的息屏退出走 `exitProcess(0)`，会记成 [ApplicationExitInfo.REASON_EXIT_SELF]——
 * 正好和「被打死」区分开。
 */
object ProcessExitLog {

    /** 读最近几次退出记录并写进 [CardPerf] 日志（「下载」目录）。API < 30 或取不到时静默跳过。 */
    fun logLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            CardPerf.mark("[diag] 上次退出原因：系统版本低于 Android 11，取不到")
            return
        }
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val records = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        }.getOrNull()
        if (records.isNullOrEmpty()) {
            CardPerf.mark("[diag] 上次退出原因：无记录（首次安装后第一次启动）")
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        records.forEachIndexed { index, info ->
            val prefix = if (index == 0) "上次退出" else "更早第${index}次"
            CardPerf.mark(
                "[diag] $prefix：${describe(info.reason)}" +
                    "（${fmt.format(Date(info.timestamp))}" +
                    "，status=${info.status}" +
                    info.description?.let { "，$it" }.orEmpty() + "）"
            )
        }
    }

    /** 退出原因 → 人话。重点是把「崩了/被杀」与「我们自己退的」分开。 */
    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "⚠ Java 层崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "⚠ 原生崩溃（SIGSEGV 等，卡层 SDK 嫌疑最大）"
        ApplicationExitInfo.REASON_ANR -> "⚠ 无响应被杀（ANR，主线程卡住）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "被系统回收（内存不足）"
        ApplicationExitInfo.REASON_SIGNALED -> "收到信号被终止"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程死亡"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App 自己退出（息屏退出 / 拔卡退出）"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束（最近任务里划掉）"
        ApplicationExitInfo.REASON_USER_STOPPED -> "用户停止了应用"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更导致重启"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用被更新"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "应用状态变更"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_OTHER -> "其他（系统未细分）"
        else -> "未知（reason=$reason）"
    }

    /** 只取最近几条：够看清「这次断连前后进程发生了什么」即可，不刷屏。 */
    private const val MAX_RECORDS = 3
}
