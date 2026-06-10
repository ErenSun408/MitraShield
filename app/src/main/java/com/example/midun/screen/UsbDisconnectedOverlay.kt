package com.example.midun.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.midun.ui.theme.*
import kotlinx.coroutines.delay

/**
 * USB断开后的全屏锁定层
 * 显示警告 + 倒计时，结束后回调跳回登录
 */
@Composable
fun UsbDisconnectedOverlay(onCountdownFinished: () -> Unit) {
    var countdown by remember { mutableIntStateOf(5) }

    // 闪烁动画
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        onCountdownFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scaleAnim)
                    .clip(CircleShape)
                    .background(Danger.copy(alpha = 0.2f * alphaAnim)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.UsbOff,
                    null,
                    tint = Danger,
                    modifier = Modifier.size(72.dp).alpha(alphaAnim)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "USB安全卡已断开",
                color = Danger,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "正在清除内存中的所有数据...",
                color = Color.White.copy(0.8f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                ClearItem("断开端到端加密连接")
                ClearItem("清除会话密钥")
                ClearItem("清除APP缓存数据")
                ClearItem("退出登录状态")
            }

            Spacer(Modifier.height(32.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${countdown}秒后APP自动退出",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "请重新插入USB安全卡后重启APP",
                color = Color.White.copy(0.5f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ClearItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White.copy(0.7f), fontSize = 12.sp)
    }
}

/**
 * DEV 专用悬浮菜单（左上角）：USB 拔插开关 + 两个跳转入口。
 * "未初始化插入"/"已初始化插入" 先改 mock 安全卡状态，再由调用方重走 Splash 路由，
 * 从而落到 Init / Login。
 * ⚠️ M10 接真实 FSShell SDK 时整组删除。
 */
@Composable
fun BoxScope.DevControlPanel(
    isConnected: Boolean,
    useRealCard: Boolean,
    onToggle: () -> Unit,
    onSimUninitInsert: () -> Unit,
    onSimInitInsert: () -> Unit,
    onSetRealCard: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.48f),
            shadowElevation = 8.dp
        ) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "DEV 菜单",
                    tint = Color.White
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isConnected) "模拟拔出USB" else "模拟插入USB") },
                leadingIcon = {
                    Icon(
                        if (isConnected) Icons.Default.Usb else Icons.Default.UsbOff,
                        contentDescription = null,
                        tint = if (isConnected) Success else Danger
                    )
                },
                onClick = {
                    expanded = false
                    onToggle()
                }
            )
            DropdownMenuItem(
                text = { Text("模拟未初始化插入") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Accent)
                },
                onClick = {
                    expanded = false
                    onSimUninitInsert()
                }
            )
            DropdownMenuItem(
                text = { Text("模拟已初始化插入") },
                leadingIcon = {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = Primary)
                },
                onClick = {
                    expanded = false
                    onSimInitInsert()
                }
            )
            // M11.3 真卡/模拟切换：真卡模式下初始化/认证走真实 FSShell SDK。
            DropdownMenuItem(
                text = { Text(if (useRealCard) "切回模拟模式" else "切到真卡模式") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Memory, contentDescription = null,
                        tint = if (useRealCard) Success else Warning
                    )
                },
                onClick = {
                    expanded = false
                    onSetRealCard(!useRealCard)
                }
            )
        }
    }
}
