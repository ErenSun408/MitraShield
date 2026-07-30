package com.example.midun

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.real.CardPerf
import com.example.midun.diag.ProcessExitLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MiDunApp : Application() {

    @Inject lateinit var cardManager: SecurityCardManager

    /**
     * **进程级**拔卡监听（`[usb]` 安全修复 2026-07-29）。
     *
     * 原先只有 [MainActivity] 注册 USB receiver，按返回键 finish Activity 后 `onDestroy` 就把它注销了；
     * 此后拔卡无人知晓，而认证状态、DEK、盘句柄都挂在 `@Singleton` 上随**进程**存活 → 再点桌面图标即可
     * 无卡进入全部功能。注册在 Application 上则只要进程还活着就收得到，把这个窗口彻底堵死。
     *
     * 只收 DETACHED：ATTACHED 侧要走 USB 权限申请等与界面相关的流程，仍留在 [MainActivity]。
     * Activity 存活时两处会各收到一次同一广播 → [SecurityCardManager] 的事件队列按序处理，第二条被
     * [com.example.midun.data.real.RealUsbManager.onDeviceDetached] 的幂等守卫直接吃掉（`[usb]` 2026-07-30
     * 修：原先两条并发跑 `closeDevice()`，其中一条可能落在刚建好的连接之后，把它清成 DISCONNECTED）。
     */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            // 是不是本卡由卡层判（RealUsbManager.onDeviceDetached），拔耳机不会误伤。
            cardManager.onUsbDetached(device?.deviceName)
        }
    }

    override fun onCreate() {
        super.onCreate() // Hilt 字段注入在此完成，cardManager 之后才可用
        // 诊断日志接线要最早（[CardPerf.attach] 幂等，卡层 init 里也会调一次）：进程启动与上次退出原因是
        // 排查「会话断开」时区分「App 自己断的」与「进程被打死」的关键，见 [ProcessExitLog]。
        CardPerf.attach(this)
        CardPerf.mark("[diag] 进程启动")
        ProcessExitLog.logLastExit(this)
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED // 受保护的系统广播
        )
    }
}
