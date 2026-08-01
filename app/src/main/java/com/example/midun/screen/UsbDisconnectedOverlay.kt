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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.midun.ui.theme.*
import kotlinx.coroutines.delay

/** 退出倒计时秒数。 */
private const val EXIT_COUNTDOWN_SEC = 5

/**
 * 插回卡后的停表上限：超过这么久还没连上（用户没点授权框、或点了拒绝），就恢复倒计时照常退出。
 * 10 秒够点掉系统授权框，又不至于让「插了别的 USB 设备」把退出拖太久。
 */
private const val RECONNECT_GRACE_MS = 10_000L

/** 停表期间的设备表轮询间隔。 */
private const val POLL_MS = 500L

/**
 * USB断开后的全屏锁定层
 * 只显示闪烁警告图标 + 「设备已断开」+ 退出倒计时（客户 2026-07-27：清理清单等说明文字一律不显示），
 * 倒计时结束后回调跳回登录。
 *
 * [isDevicePresent]：此刻系统里还有没有 USB 设备。**有就停表**（客户 2026-08-01 报「5 秒内插回并授权，仍然
 * 退出」）：插回后 SDK 首次 Open 必然失败并弹系统授权框，要等用户点「允许」再重开一次，成功之后状态才离开
 * DISCONNECTED——而找到并点掉那个框往往超过 5 秒。倒计时照走的话，退出指令已经发出，界面刚跳回登录页就被
 * 结束掉。
 *
 * 判据取**轮询系统设备表**而非 ATTACHED 广播（第一版就栽在这）：插卡时系统会因 Manifest 里的
 * `USB_DEVICE_ATTACHED` intent-filter 去拉起 Activity，广播投递、Activity 实例、授权框三者时序不可控，
 * 广播晚到或落到另一个实例上，停表就白设了。设备表是当场问系统，不会漏。
 *
 * 停表总共只给 [RECONNECT_GRACE_MS]，用完照常倒计时退出——否则插着别的 USB 设备（鼠标、扩展坞）就永远退不出去。
 */
@Composable
fun UsbDisconnectedOverlay(isDevicePresent: () -> Boolean, onCountdownFinished: () -> Unit) {
    var countdown by remember { mutableIntStateOf(EXIT_COUNTDOWN_SEC) }
    var waitingReconnect by remember { mutableStateOf(false) }

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

    // 每 500ms 问一次系统：设备回来了就停表（累计最多 [RECONNECT_GRACE_MS]），否则照常走秒。
    // 期间真连上了，本层会整个离开组合、协程随之取消，退出永不发生。
    LaunchedEffect(Unit) {
        var graceLeftMs = RECONNECT_GRACE_MS
        while (countdown > 0) {
            val present = runCatching { isDevicePresent() }.getOrDefault(false)
            if (present && graceLeftMs > 0) {
                waitingReconnect = true
                delay(POLL_MS)
                graceLeftMs -= POLL_MS
                countdown = EXIT_COUNTDOWN_SEC // 停表期间不许走秒，等回来再从头数
                continue
            }
            waitingReconnect = false
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
                "设备已断开",
                color = Danger,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

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
                        if (waitingReconnect) "检测到设备，正在重新连接…" else "${countdown}秒后APP自动退出",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
