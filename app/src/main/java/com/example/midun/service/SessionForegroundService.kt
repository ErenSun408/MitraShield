package com.example.midun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.midun.MainActivity
import com.example.midun.R
import com.example.midun.data.real.CardPerf

/**
 * 会话期间的前台服务（`[network]` 2026-08-01 客户故障「发图片/文件时断连」定案后新增）。
 *
 * **为什么非它不可**：2026-08-01 的现场日志把断连原因钉死了——`[net] Software caused connection abort`
 * 与 `[diag] ⚠ 本应用的网络被系统拦截` 同一毫秒发生，且拦截时进程重要度是「已缓存(后台)」或
 * 「TOP_SLEEPING(熄屏)」。也就是说：**用户一去开系统文件/图片选取器（或一锁屏），本应用被判为后台，系统当场
 * 断掉它的网**，TCP 被本机协议栈中止。这解释了「断连总发生在发文件时」——不是传输把连接压垮的（793KB 的文件
 * 0 秒就传完了），是**打开选取器这个动作**把 App 推到了后台。
 *
 * 已有的 [com.example.midun.network.SessionKeepAlive] 只按住 CPU 与 WiFi，改变不了「你是后台应用」这个身份，
 * 对这类拦截无效。前台服务是 Android 上唯一正规的解法：跑着它，进程重要度被钉在
 * [android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE]，Doze、待机分组、
 * 数据保护的后台限制以及缓存进程冻结全部不再适用。
 *
 * **起止时机**：会话进入 CONNECTED 就起、离开就停（接线在 `P2PSessionManager.init`）。不能只在「有在途传输」
 * 时起——用户是在**还没有任何传输**的时候打开选取器的，等文件选回来会话早断了。拔卡/登出/自动锁定同样会把
 * 状态打回 DISCONNECTED，故那几条路径无需另行接线。
 *
 * **隐私**：通知**不写任何文字**——标题与正文都不设，只剩系统强制显示的应用名（那一行框架自己画，App 关不掉）；
 * 并设 `VISIBILITY_SECRET`，锁屏上连应用名都不出现。绝不可把对端备注或消息内容放进去，那等于在锁屏上广播
 * 「你此刻在和谁聊天」。
 */
class SessionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // 进程被杀后不要自动重启：会话是内存态，重启起来也只是个空壳通知。
        if (!enterForeground()) stopSelf()
        return START_NOT_STICKY
    }

    /** 起前台。返回 false 表示系统拒绝了（必须 [stopSelf]，否则会因未调用 startForeground 被判 ANR）。 */
    private fun enterForeground(): Boolean {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            // 不设标题/正文：系统强制显示的应用名已是最低限度，多一个字都是多余的暴露面（客户要求 2026-08-01）。
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // 锁屏不显示：不泄露「正在聊天」这件事
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
        }
        // connectedDevice 语义最贴切（安全卡经 USB 接入 + 两台设备直连），且无 Android 15 给 dataSync 的
        // 6 小时/天上限。Android 14+ 要求该类型有前置条件（此处由「USB 设备已接入」满足，本 App 真卡-only
        // 故必然成立）；万一某些 ROM 判不过，退到 dataSync，再不行退到无类型，总之不能让服务卡在未起前台。
        val types = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        for (type in types) {
            if (runCatching { startForeground(NOTIFICATION_ID, notification, type) }.isSuccess) return true
        }
        return runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { CardPerf.mark("[net] 前台服务启动被拒：${it.javaClass.simpleName}") }
            .isSuccess
    }

    /** 点通知回到 App，不带任何参数（不暴露是哪个会话）。 */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** 低优先级渠道：无声、无横幅、无角标，安静待在通知栏里，会话结束即消失。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "安全会话", NotificationManager.IMPORTANCE_LOW).apply {
            description = "会话进行期间保持连接不被系统中断"
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "midun_session"
        private const val NOTIFICATION_ID = 1001

        /**
         * 会话建立时调用。失败静默：Android 12+ 禁止从后台启动前台服务，若用户恰好在建联瞬间切走会抛
         * [android.app.ForegroundServiceStartNotAllowedException]——那种情况下保活本就无从谈起，不该连累会话。
         */
        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { CardPerf.mark("[net] 前台服务未能启动：${it.javaClass.simpleName}") }
        }

        /** 会话结束（含拔卡/登出/自动锁定）时调用。重复调用无害。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionForegroundService::class.java)) }
        }
    }
}
