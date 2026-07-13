package com.example.midun

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.example.midun.navigation.NavGraph
import com.example.midun.ui.theme.MiDunTheme
import com.example.midun.viewmodel.DeviceViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用入口（无卡版）。无安全卡 = 无 USB 插拔、无「等待插卡」中间态：账户门面在启动时即
 * [com.example.midun.data.SecurityCardManager] `connect()`，状态直达 CONNECTED，Splash 据本地 `.auth`
 * 是否存在路由到登录 / 初始化。故本 Activity 只保留防截屏、前后台自动锁定钩子，并始终渲染 NavGraph。
 *
 * 历史：真卡版在此注册 USB 广播、按卡插拔渲染 NavGraph / 显 `UsbDisconnectedOverlay` 等待遮罩——
 * bobo-nocard 全部移除。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏 / 录屏 / 最近任务缩略图泄露隐私。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        // 自动锁定超时的前后台钩子（无活动 N 分钟登出）。
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { deviceViewModel.onAppBackground() }
            override fun onStart(owner: LifecycleOwner) { deviceViewModel.onAppForeground() }
        })

        setContent {
            MiDunTheme {
                NavGraph(navController = rememberNavController())
            }
        }
    }
}
