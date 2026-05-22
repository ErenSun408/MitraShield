package com.example.midun.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.midun.data.UsbState
import com.example.midun.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    val usbConnected = UsbState.isConnected

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
                Icon(
                    Icons.Default.Security,
                    null,
                    tint = Accent,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("密盾", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (usbConnected) Icons.Default.Usb else Icons.Default.UsbOff,
                        null,
                        tint = if (usbConnected) Success else Danger,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (usbConnected) "USB安全卡已连接" else "USB安全卡未连接",
                        color = if (usbConnected) Success else Danger,
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
                Text("密码经SHA256加密传输至安全卡验证", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        showError = false
                    },
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
                    isError = showError,
                    supportingText = if (showError) {{ Text("密码错误，请重试") }} else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(20.dp))

                if (!usbConnected) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Danger.copy(0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Danger, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("操作异常：请先插入USB安全卡", color = Danger, fontSize = 12.sp)
                        }
                    }
                }

                Button(
                    onClick = {
                        isLoading = true
                        showError = false
                    },
                    enabled = usbConnected && password.isNotEmpty() && !isLoading,
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

                LaunchedEffect(isLoading) {
                    if (isLoading) {
                        delay(1500)
                        isLoading = false
                        onLoginSuccess()
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("设备ID: MI-X8F2K9A3", fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("SN: SC-2026051300001", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}