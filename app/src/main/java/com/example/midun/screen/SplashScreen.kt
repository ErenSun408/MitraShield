package com.example.midun.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.midun.R
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.navigation.Screen
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.DeviceViewModel
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    navController: NavController,
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    var startAnim by remember { mutableStateOf(false) }
    var animationDone by remember { mutableStateOf(false) }
    val deviceStatus by deviceViewModel.deviceStatus.collectAsState()

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0f,
        animationSpec = tween(1200)
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0.6f,
        animationSpec = tween(1000, easing = EaseOutBack)
    )

    LaunchedEffect(Unit) {
        startAnim = true
        delay(2500)
        animationDone = true
    }

    LaunchedEffect(animationDone, deviceStatus) {
        if (!animationDone) return@LaunchedEffect
        val target = when {
            // DISCONNECTED 不会进到这（MainActivity 在无卡时不渲染 NavGraph）；CONNECTING=正在检测卡 → 停留显本页。
            deviceStatus.status == UsbDeviceStatus.DISCONNECTED ||
                deviceStatus.status == UsbDeviceStatus.CONNECTING -> null
            !deviceStatus.isInitialized -> Screen.Init.route
            deviceStatus.status == UsbDeviceStatus.AUTHENTICATED -> Screen.Main.route
            else -> Screen.Login.route
        }
        if (target != null) {
            navController.navigate(target) {
                popUpTo(Screen.Splash.route) { inclusive = true }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PrimaryDark, Primary, PrimaryLight)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            Image(
                painter = painterResource(R.drawable.bobo_icon_fg),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "波波",
                color = CardBg,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "愿我们在平淡里听见彼此的声音",
                color = CardBg.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在检测设备...",
                color = CardBg.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }

        // 驱动模式测试入口：左上角三点按钮。鸿蒙 2.0 上连卡要好几分钟，测试人员在这一屏等待时
        // 就能直接换模式，不必先熬到登录页。诊断脚手架，模式定下来后整体移除。
        DriverModeEntry(
            deviceViewModel = deviceViewModel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 24.dp, start = 8.dp)
        )
    }
}
