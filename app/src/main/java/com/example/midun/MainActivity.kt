package com.example.midun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.navigation.NavGraph
import com.example.midun.navigation.Screen
import com.example.midun.screen.UsbDisconnectedOverlay
import com.example.midun.ui.theme.MiDunTheme
import com.example.midun.viewmodel.DeviceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 广播里的「是哪台设备」：插拔两侧都要据此判是不是我们那张卡（拔出侧漏判会把拔耳机当成拔卡）。
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> deviceViewModel.onUsbAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> deviceViewModel.onUsbDetached(device)
            }
        }
    }

    /**
     * 息屏自动退出（用户可选：关闭 / 立即 / 10 秒）。息屏广播只能动态注册（Manifest 静态注册无效），
     * 故在此监听 [Intent.ACTION_SCREEN_OFF]/[Intent.ACTION_SCREEN_ON]，判定与计时交给 [DeviceViewModel]，
     * 真要退出时由 [exitApp] 执行。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> deviceViewModel.onScreenOff { exitApp() }
                Intent.ACTION_SCREEN_ON -> deviceViewModel.onScreenOn()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // 启动时若已插着卡，立即连接（USB 插拔广播只覆盖启动后的新插入）。无卡则保持 DISCONNECTED
        // → 由 UsbDisconnectedOverlay 提示插卡。
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.deviceList.isNotEmpty()) {
            deviceViewModel.onUsbAttached(usbManager.deviceList.values.first())
        }

        // 息屏/亮屏广播（受保护系统广播，用 NOT_EXPORTED 注册即可）。
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { deviceViewModel.onAppBackground() }
            override fun onStart(owner: LifecycleOwner) { deviceViewModel.onAppForeground() }
        })

        setContent {
            MiDunTheme {
                val navController = rememberNavController()
                val deviceStatus by deviceViewModel.deviceStatus.collectAsState()
                val status = deviceStatus.status

                // 本会话是否连过卡：连过一次后再拔卡才触发「5秒退出遮罩」；从没连过(刚开 App 没插卡)只白屏等待。
                var hasConnectedBefore by remember { mutableStateOf(false) }
                LaunchedEffect(status) {
                    if (status == UsbDeviceStatus.CONNECTED || status == UsbDeviceStatus.AUTHENTICATED) {
                        hasConnectedBefore = true
                    }
                }

                // de-auth 守卫（修复闪退根因A）：自动锁定 / 会话失效使状态从 AUTHENTICATED 跌回 CONNECTED 时，
                // 主流程界面（Main/私藏/会话等）仍停在已 SFCloseDisk 的盘上，用户下一次卡操作会命中已关闭句柄 →
                // 原生 SIGSEGV 崩溃。此处监听「认证态跌落且未拔卡」，导回 Splash 让其重新路由到登录页。
                // 拔卡（DISCONNECTED）另由下方全屏遮罩兜底，不在此处理。
                var wasAuthenticated by remember { mutableStateOf(false) }
                LaunchedEffect(status) {
                    when (status) {
                        UsbDeviceStatus.AUTHENTICATED -> wasAuthenticated = true
                        // 仅拦「已初始化卡的认证态跌落」（自动锁定/会话失效）。恢复出厂会把状态置为
                        // CONNECTED+isInitialized=false，那条路径由其自身的 navigate(Init) 收尾，不在此拦截，
                        // 否则会多插一次 Splash 闪屏。
                        UsbDeviceStatus.CONNECTED -> if (wasAuthenticated && deviceStatus.isInitialized) {
                            wasAuthenticated = false
                            // 已在登录/初始化/闪屏区（如设置里手动登出已自行导航）则不重复导航。
                            val route = navController.currentDestination?.route
                            if (route != Screen.Splash.route &&
                                route != Screen.Login.route &&
                                route != Screen.Init.route
                            ) {
                                navController.navigate(Screen.Splash.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                        else -> { /* DISCONNECTED / CONNECTING / ERROR：不在此处理 */ }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 未插卡等待态背景（波波换皮）：无卡时显示水墨背景，替代原纯白等待屏。
                    // 用 FillWidth + 顶部对齐显示完整满宽诗句（Crop 会按高缩放裁掉左右两端、切掉「遇…景」）；
                    // 图比屏矮时底部空隙用图底色 #153B5D 填充，无缝衔接。
                    if (status == UsbDeviceStatus.DISCONNECTED) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF133757))
                        ) {
                            Image(
                                painter = painterResource(R.drawable.nocard_bg),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                alignment = Alignment.TopCenter,
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }

                    // 无卡(DISCONNECTED)时不渲染主导航：从没连过 → 纯白等待(下方什么都不画)；连过又拔 → 退出遮罩。
                    // 有卡(CONNECTING 检测中 / CONNECTED / AUTHENTICATED)才渲染 NavGraph(Splash 自显「检测中」并路由)。
                    if (status != UsbDeviceStatus.DISCONNECTED) {
                        NavGraph(navController = navController)
                    }

                    if (status == UsbDeviceStatus.DISCONNECTED && hasConnectedBefore) {
                        // 放进独立全屏 Dialog 窗口，确保拔卡锁定层盖在所有 AlertDialog（删除确认/选取等）之上——
                        // Compose 的 AlertDialog 是独立平台窗口，若遮罩只画在 Activity 内容里会被这些 dialog 盖住。
                        Dialog(
                            onDismissRequest = {},
                            properties = DialogProperties(
                                usePlatformDefaultWidth = false, // 占满全屏
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            )
                        ) {
                            UsbDisconnectedOverlay(onCountdownFinished = {
                                finishAndRemoveTask()
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(screenReceiver)
    }

    /** 彻底退出：结束整个任务栈 + 移出最近任务，再硬杀进程清掉内存明文。下次启动经 Splash 重新登录。 */
    private fun exitApp() {
        finishAndRemoveTask()
        exitProcess(0)
    }
}