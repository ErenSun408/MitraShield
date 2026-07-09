package com.example.midun.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
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
    val isTestMode by deviceViewModel.isTestMode.collectAsState()

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

    LaunchedEffect(animationDone, deviceStatus, isTestMode) {
        // 无卡测试模式：跳过卡检测/动画等待，直奔主页（绕过 Init/Login，认证要卡）。
        if (isTestMode) {
            navController.navigate(Screen.Main.route) {
                popUpTo(Screen.Splash.route) { inclusive = true }
            }
            return@LaunchedEffect
        }
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
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(80.dp)
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
                text = "正在检测USB安全卡...",
                color = CardBg.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
    }
}
