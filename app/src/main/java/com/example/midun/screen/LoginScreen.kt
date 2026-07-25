package com.example.midun.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.R
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.AuthViewModel
import com.example.midun.viewmodel.DeviceViewModel

/**
 * 登录页「忘记密码？」入口开关（暂时隐藏）。真卡在未登录态调 wipeAll 必然失败
 * （`RealUsbManager.wipeAll` 未 AUTHENTICATED 直接诚实报错、指向 PC 串口工具），
 * 入口只会把用户引到一条走不通的路。置 true 即恢复，弹框与擦卡逻辑原样保留。
 */
private const val SHOW_FORGOT_PASSWORD = false

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onForgotPassword: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showForgotDialog by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    var wipeError by remember { mutableStateOf<String?>(null) }
    // 驱动模式测试面板（左上角三点按钮打开，测鸿蒙 2.0 登录慢）：
    var showDriverPanel by remember { mutableStateOf(false) }
    val driverMode by deviceViewModel.driverMode.collectAsState()
    val perfLog by deviceViewModel.perfLog.collectAsState()
    val devStatus by deviceViewModel.deviceStatus.collectAsState()

    val loginState by authViewModel.loginState.collectAsState()
    val isLoading = loginState is AuthViewModel.LoginState.Loading
    val error = loginState as? AuthViewModel.LoginState.Error

    LaunchedEffect(loginState) {
        if (loginState is AuthViewModel.LoginState.Success) onLoginSuccess()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部渐变背景
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.verticalGradient(listOf(Primary, PrimaryLight))
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.bobo_icon_fg),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text("波波", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 能停在此屏即 USB 必连（断开由全局 UsbDisconnectedOverlay 兜底），故静态显示。
                    Icon(
                        Icons.Default.Usb,
                        null,
                        tint = Success,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "设备已连接",
                        color = Success,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 登录卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 240.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("安全登录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("密码加密后传输至设备验证", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("安全密码") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = if (error != null) {
                        {
                            Text(
                                if (error.attemptsLeft > 0) "${error.message}（剩余${error.attemptsLeft}次）"
                                else error.message,
                                color = Danger
                            )
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { authViewModel.login(password) }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { authViewModel.login(password) },
                    enabled = password.isNotEmpty() && !isLoading && error?.attemptsLeft != 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("登 录", fontSize = 16.sp)
                    }
                }

                if (SHOW_FORGOT_PASSWORD) {
                    TextButton(onClick = { showForgotDialog = true }) {
                        Text(
                            "忘记密码？",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 驱动模式测试入口：左上角三点按钮（原 logo 连点 3 下的隐藏手势客户点不出来，改成看得见点得到的按钮）。
        // 诊断脚手架，模式定下来后连同面板整体移除。
        IconButton(
            onClick = { showDriverPanel = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 24.dp, start = 8.dp)
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "连接模式", tint = Color.White)
        }
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { if (!wiping) showForgotDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text(if (wiping) "正在清除数据" else "危险操作", color = Danger) },
            text = {
                if (wiping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Danger,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("正在清除设备内所有数据...", color = TextSecondary)
                    }
                } else {
                    Column {
                        wipeError?.let {
                            Text(it, color = Danger, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            "忘记密码只能通过恢复出厂设置解决。\n\n" +
                                "此操作将永久删除设备内所有文件、聊天记录和密码，且无法恢复。\n\n" +
                                "确认后需要重新初始化设备。",
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                if (!wiping) {
                    Button(
                        onClick = {
                            wiping = true
                            wipeError = null
                            deviceViewModel.wipeAndReset(
                                onComplete = onForgotPassword,
                                onError = { wiping = false; wipeError = it }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)
                    ) { Text("我已了解，清除所有数据") }
                }
            },
            dismissButton = {
                if (!wiping) {
                    TextButton(onClick = { showForgotDialog = false }) {
                        Text("取消", color = TextSecondary)
                    }
                }
            }
        )
    }

    if (showDriverPanel) {
        DriverModePanel(
            currentMode = driverMode,
            statusText = when (devStatus.status) {
                UsbDeviceStatus.AUTHENTICATED, UsbDeviceStatus.CONNECTED -> "已连接"
                UsbDeviceStatus.CONNECTING -> "连接中…"
                else -> "未连接"
            },
            perfLog = perfLog,
            onSelect = { deviceViewModel.switchDriverMode(it) },
            onDismiss = { showDriverPanel = false }
        )
    }
}

/**
 * 驱动模式测试面板（登录页 logo 连点 3 下弹出）：切 0/2 即持久化 + 重连，实时显示连接状态与卡层耗时，
 * 供测试人员在鸿蒙 2.0 上对比登录快慢。文案极简（测试人员不懂技术）。定位完成后连同接线整体移除。
 */
@Composable
private fun DriverModePanel(
    currentMode: Int,
    statusText: String,
    perfLog: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(20.dp)
        ) {
            Column {
                Text("连接模式", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("切换后自动重连，看下方耗时对比快慢", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeChoice("模式 0", currentMode == 0, Modifier.weight(1f)) { onSelect(0) }
                    ModeChoice("模式 2", currentMode == 2, Modifier.weight(1f)) { onSelect(2) }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("状态：", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (statusText == "已连接") Success else Warning
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("连接耗时", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        perfLog.ifBlank { "（暂无，插卡或切换后出现）" },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = Primary)
                }
            }
        }
    }
}

/** 驱动模式面板里的一个模式选项块（选中高亮）。 */
@Composable
private fun ModeChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.5.dp,
                if (selected) Primary else TextSecondary.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp)
            )
            .background(if (selected) Primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Primary else TextSecondary
        )
    }
}