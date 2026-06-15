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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.navigation.NavGraph
import com.example.midun.navigation.Screen
import com.example.midun.screen.DevControlPanel
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

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.deviceList.isNotEmpty()) {
            deviceViewModel.onUsbAttached(usbManager.deviceList.values.first())
        } else {
            // Mock-phase convenience: emulator has no real USB, so seed CONNECTED
            // state so UsbDisconnectedOverlay doesn't fire at every launch.
            // DELETE this else branch when the real FSShell SDK lands in M10.
            deviceViewModel.onUsbAttached(null)
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { deviceViewModel.onAppBackground() }
            override fun onStart(owner: LifecycleOwner) { deviceViewModel.onAppForeground() }
        })

        setContent {
            MiDunTheme {
                val navController = rememberNavController()
                val deviceStatus by deviceViewModel.deviceStatus.collectAsState()
                val isConnected = deviceStatus.status != UsbDeviceStatus.DISCONNECTED
                val useRealCard by deviceViewModel.useRealCard.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(navController = navController)

                    if (!isConnected) {
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

                    // DEV-only: jump into Init/Login by faking card state then
                    // re-routing through Splash (real routing path). Remove with SDK in M10.
                    DevControlPanel(
                        isConnected = isConnected,
                        useRealCard = useRealCard,
                        onToggle = { deviceViewModel.debugToggleUsb() },
                        onSimUninitInsert = {
                            deviceViewModel.debugSimulateFirstInsert()
                            navController.navigate(Screen.Splash.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onSimInitInsert = {
                            deviceViewModel.debugSimulateInitializedInsert()
                            navController.navigate(Screen.Splash.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onSetRealCard = { real ->
                            deviceViewModel.setUseRealCard(real)
                            // 切换后重走 Splash 路由，按真卡/模拟的设备状态落到 Init/Login。
                            navController.navigate(Screen.Splash.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}