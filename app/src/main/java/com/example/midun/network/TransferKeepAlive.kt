package com.example.midun.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * 文件传输期间的保活（`[network]` 2026-07-30 客户故障「发图片时会话断开」）。
 *
 * 传一张图片是几十秒到几分钟的事（每块 64KB、每块写一次卡、全在 `fsShell` 全局锁上串行），而用户点了发送
 * 就会把手机放下。原先 App 没有任何保活手段（连 `WAKE_LOCK` 权限都没申请）：屏一灭 CPU 睡下去，心跳协程
 * 不再按时醒、对端的帧也没人读，socket 就这么被自己判死。发文件本就该在传输期间按住 CPU 与 WiFi。
 *
 * **引用计数**：收发可能同时在跑（TCP 全双工，一条通道双向都能传），故 [acquire]/[release] 配对计数，
 * 计数归零才真正释放。
 *
 * **必定释放**：WakeLock 自带 [MAX_HOLD_MS] 超时，万一有一条路径漏了 [release]（异常/取消），系统也会
 * 到点收回，不会把用户的电按住不放。
 */
class TransferKeepAlive(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val wakeLock: PowerManager.WakeLock? = runCatching {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
    }.getOrNull()

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF：API 29 起废弃但仍是「传输期间别让 WiFi 打盹」的可用手段
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
    }.getOrNull()

    private var holders = 0

    /** 有传输开始 → 按住 CPU/WiFi（首个持有者才真正加锁）。 */
    @Synchronized
    fun acquire() {
        holders++
        if (holders != 1) return
        runCatching { wakeLock?.acquire(MAX_HOLD_MS) }
        runCatching { wifiLock?.acquire() }
    }

    /** 一段传输结束 → 释放（最后一个持有者才真正解锁）。 */
    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders != 0) return
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    private companion object {
        const val WAKE_TAG = "MiDun:fileTransfer"
        const val WIFI_TAG = "MiDun:fileTransfer"

        /** WakeLock 兜底超时：10 分钟。100MB 上限的文件在最慢的卡上也该在此之内结束。 */
        const val MAX_HOLD_MS = 10 * 60 * 1000L
    }
}
