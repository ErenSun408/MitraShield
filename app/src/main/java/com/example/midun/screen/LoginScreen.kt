package com.example.midun.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.R
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.AuthViewModel
import com.example.midun.viewmodel.DeviceViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onForgotPassword: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showForgotDialog by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    var wipeError by remember { mutableStateOf<String?>(null) }

    val loginState by authViewModel.loginState.collectAsState()
    val isLoading = loginState is AuthViewModel.LoginState.Loading
    val error = loginState as? AuthViewModel.LoginState.Error

    val context = LocalContext.current
    val deviceStatus by deviceViewModel.deviceStatus.collectAsState()
    val phoneId = remember { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "未知" }

    // 预填注册手机号（NC7）：connect() 读出的 registeredPhone 到达后填入，不覆盖用户已输入的内容。
    LaunchedEffect(deviceStatus.registeredPhone) {
        if (phone.isEmpty()) deviceStatus.registeredPhone?.let { phone = it }
    }

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
                Text("手机号 + 密码登录本机账户", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 11 && it.all { c -> c.isDigit() }) phone = it },
                    label = { Text("手机号") },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(12.dp))

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
                    keyboardActions = KeyboardActions(onDone = { authViewModel.login(phone, password) }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { authViewModel.login(phone, password) },
                    enabled = phone.isNotEmpty() && password.isNotEmpty() && !isLoading && error?.attemptsLeft != 0,
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

                TextButton(onClick = { showForgotDialog = true }) {
                    Text(
                        "忘记密码？",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(16.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("设备SN: ${deviceStatus.deviceId.ifEmpty { "未知" }}", fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    // 本机ID 行配对等图标，使两行文本左缘对齐（原来无图标从 0 起、与 SN 行错位）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Smartphone, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("本机ID: $phoneId", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
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
}