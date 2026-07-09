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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    deviceViewModel.onUsbAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    deviceViewModel.onUsbDetached()
                }
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

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { deviceViewModel.onAppBackground() }
            override fun onStart(owner: LifecycleOwner) { deviceViewModel.onAppForeground() }
        })

        setContent {
            MiDunTheme {
                val navController = rememberNavController()
                val deviceStatus by deviceViewModel.deviceStatus.collectAsState()
                val status = deviceStatus.status
                val isTestMode by deviceViewModel.isTestMode.collectAsState()

                // 本会话是否连过卡：连过一次后再拔卡才触发「5秒退出遮罩」；从没连过(刚开 App 没插卡)只白屏等待。
                var hasConnectedBefore by remember { mutableStateOf(false) }
                LaunchedEffect(status) {
                    if (status == UsbDeviceStatus.CONNECTED || status == UsbDeviceStatus.AUTHENTICATED) {
                        hasConnectedBefore = true
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 未插卡等待态背景（波波换皮）：无卡且非测试模式时显示水墨背景，替代原纯白等待屏。
                    // 用 FillWidth + 顶部对齐显示完整满宽诗句（Crop 会按高缩放裁掉左右两端、切掉「遇…景」）；
                    // 图比屏矮时底部空隙用图底色 #153B5D 填充，无缝衔接。
                    if (status == UsbDeviceStatus.DISCONNECTED && !isTestMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF153B5D))
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
                    // 无卡测试模式(isTestMode)：无视卡状态强制渲染 NavGraph，Splash 据 testMode 直跳 Main。
                    if (status != UsbDeviceStatus.DISCONNECTED || isTestMode) {
                        NavGraph(navController = navController)
                    }

                    // 常态三点面板入口（[[project_midun_nocard_testmode]] T1）：画在 NavGraph 之上，DISCONNECTED
                    // 白屏时也可见。进入测试模式后隐藏（退出靠重启）。
                    if (!isTestMode) {
                        TestModeEntry(
                            onEnterTestMode = {
                                deviceViewModel.enableTestMode()
                                // NavGraph 已组合（有卡在 Login/Init）→ 直接跳 Main；未组合（无卡白屏）→ navigate
                                // 抛异常被吞，转由本次 testMode 触发新组合的 Splash 按 testMode 路由到 Main。
                                runCatching {
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            }
                        )
                    }

                    // 测试模式下拔卡遮罩不触发（无卡是常态）。
                    if (status == UsbDeviceStatus.DISCONNECTED && hasConnectedBefore && !isTestMode) {
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
    }
}

/**
 * 常态三点面板入口（[[project_midun_nocard_testmode]] T1）：左上角小按钮，点开下拉里有「无卡测试」。
 * 画在 NavGraph 之上、独立于卡状态，故无卡白屏时也能进测试模式。视觉为 T3 收尾项，当前求可见可点。
 */
@Composable
private fun BoxScope.TestModeEntry(onEnterTestMode: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(4.dp)
    ) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.Gray)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("无卡测试") },
                onClick = {
                    expanded = false
                    onEnterTestMode()
                }
            )
        }
    }
}