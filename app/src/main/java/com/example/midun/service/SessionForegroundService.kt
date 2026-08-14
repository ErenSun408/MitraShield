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
import com.example.midun.network.P2PSessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.system.exitProcess

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
 * **起止时机**：连接状态进入 LISTENING / CONNECTING / CONNECTED 就起、回到 DISCONNECTED / FAILED 就停
 * （接线在 `P2PSessionManager.init`）。不能只在「有在途传输」时起——用户是在**还没有任何传输**的时候打开
 * 选取器的，等文件选回来会话早断了。拔卡/登出/自动锁定同样会把状态打回 DISCONNECTED，故那几条路径无需
 * 另行接线。
 *
 * **为什么从 LISTENING 而不是 CONNECTED 起**（`[network]` 2026-08-14）：出码方生成邀请码后多半立刻切去微信
 * 转发，等对端连上时本进程早已在后台，而 Android 12+ 禁止后台启动前台服务（[start] 那句「失败静默」正是
 * 为此写的）——也就是说在最需要它的那条路径上，这个服务此前很可能根本没起来过。改到出码/扫码当场起，
 * 启动发生在用户还站在页面上的时候，限制不适用。
 *
 * **它保不了什么**：华为/荣耀系「允许后台活动」关掉时是按 UID 整体断网，与前台服务无关（2026-08-02 现场
 * 日志：会话进行中进程仍被判「已缓存(后台)」并被断网）。那道开关只能由用户在系统设置里开，见
 * [com.example.midun.screen.BackgroundActivityGuide]。
 *
 * **隐私**：通知**不写任何文字**——标题与正文都不设，只剩系统强制显示的应用名（那一行框架自己画，App 关不掉）；
 * 并设 `VISIBILITY_SECRET`，锁屏上连应用名都不出现。绝不可把对端备注或消息内容放进去，那等于在锁屏上广播
 * 「你此刻在和谁聊天」。
 */
@AndroidEntryPoint
class SessionForegroundService : Service() {

    /** 上划清理时要亲手拆掉的那条会话，见 [onTaskRemoved]。 */
    @Inject lateinit var sessionManager: P2PSessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // 进程被杀后不要自动重启：会话是内存态，重启起来也只是个空壳通知。
        if (!enterForeground()) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * 上划清理任务栈 = 用户明确表示「我要退了」，这里把进程整个带走（`[security]` 2026-08-03 客户故障）。
     *
     * **本服务自己就是那个 bug 的成因**：会话连着时它把进程钉在
     * [android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE]，于是上划只销毁
     * Activity、进程照活 —— 而认证态、DEK、已开的盘句柄、聊天 socket 与会话密钥全挂在 `@Singleton` 上随
     * 进程存活。再点桌面图标（或点通知栏这条常驻通知）→ `MainActivity.onCreate` → `revalidatePresence()`
     * 卡还插着 → `SplashScreen` 读到 AUTHENTICATED → **直接进主界面，一次登录都不用**；对端也一直看到
     * 「已连接」。加前台服务之前上划会连进程一起被系统收走，这个洞一直被「进程死了」天然堵着。
     *
     * 兜底的「后台 5 分钟无操作自动登出」在这里指望不上：用户几十秒内点回来，`onAppForeground()` 就把
     * 计时取消了。
     *
     * **为什么不用 `android:stopWithTask="true"`**：设了它系统就不再回调本方法（见
     * [android.content.pm.ServiceInfo.FLAG_STOP_WITH_TASK] 的说明），只是把服务停掉——认证态与 socket 仍
     * 留在进程里，等系统何时回收空进程，不确定也不可控。要的是当场拆干净，所以走这条路。
     *
     * 先 [P2PSessionManager.disconnect] 再退：它同步 close socket，对端 `readLine` 当场返回 null → 正常掉线，
     * 顺带治了「本机已退、对端还显示在线」的幽灵连接。`disconnect` 全程只做 cancel/close，不阻塞主线程。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // CardPerf 是同步刷盘的，这一行在 exitProcess 之前一定落到「下载」目录，回传日志里能看见。
        CardPerf.mark("[security] 任务栈被移除（上划清理）→ 拆会话、退进程")
        runCatching { sessionManager.disconnect("上划清理任务栈") }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) } // 常驻通知一并消失，不留「点进去免登录」的入口
        stopSelf()
        exitProcess(0)
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
