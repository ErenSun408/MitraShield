package com.example.midun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.midun.data.UsbState
import com.example.midun.navigation.NavGraph
import com.example.midun.screen.UsbDisconnectedOverlay
import com.example.midun.screen.UsbToggleButton
import com.example.midun.ui.theme.MiDunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // APP启动时重置USB状态为已插入
        UsbState.connect()
        enableEdgeToEdge()
        setContent {
            MiDunTheme {
                val navController = rememberNavController()

                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(navController = navController)

                    // USB断开 - 全屏锁定动画，5秒后关闭APP
                    if (!UsbState.isConnected) {
                        UsbDisconnectedOverlay(onCountdownFinished = {
                            finishAndRemoveTask()
                        })
                    }

                    // USB拔插演示开关 - 所有页面都显示，断开状态时盖在覆盖层之上
                    UsbToggleButton(
                        isConnected = UsbState.isConnected,
                        onToggle = { UsbState.toggle() }
                    )
                }
            }
        }
    }
}