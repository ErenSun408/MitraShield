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
import com.example.midun.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var startAnim by remember { mutableStateOf(false) }
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
        onFinished()
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
                text = "密盾",
                color = CardBg,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "您的安全由我们守护",
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