package com.example.midun.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.DeviceViewModel

/**
 * 设置 Tab。
 *
 * 导航约定（与全局回调风格一致，不持 navController）：
 * - [onLogoutComplete]：本屏调 [DeviceViewModel.logout] 之后由 NavGraph 决定去向（当前接 Splash → 自然路由到 Login）。
 * - [onFactoryResetComplete]：M7.2 的"恢复出厂"成功后由 NavGraph 接 `navigate(Init){popUpTo(0)}`，本屏只负责回调。
 *
 * M7.1 范围：骨架重写 + 设备信息真值 + 退出登录。其余动作按钮（密钥更新/绑定/自动锁定/一键清理/恢复出厂）为占位，
 * 接线在 M7.2 ~ M7.5 完成。
 */
@Composable
fun SettingsScreen(
    onLogoutComplete: () -> Unit,
    onFactoryResetComplete: () -> Unit,
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    val deviceStatus by deviceViewModel.deviceStatus.collectAsState()

    // 一键清理 / 恢复出厂：M7.2 带密码确认的危险操作弹框。
    var showCleanDialog by remember { mutableStateOf(false) }
    var cleanPassword by remember { mutableStateOf("") }
    var cleanError by remember { mutableStateOf("") }
    var cleanLoading by remember { mutableStateOf(false) }

    var showResetDialog by remember { mutableStateOf(false) }
    var resetPassword by remember { mutableStateOf("") }
    var resetError by remember { mutableStateOf("") }
    var resetLoading by remember { mutableStateOf(false) }

    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ── 设备信息 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("设备信息", color = Primary)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Primary.copy(0.05f))
        ) {
            Column(Modifier.padding(16.dp)) {
                SettingsInfoItem("设备ID", deviceStatus.deviceId.ifEmpty { "未知" })
                SettingsInfoItem(
                    "绑定状态",
                    if (deviceStatus.boundPhoneId != null) "已绑定本机" else "未绑定"
                )
                // M10 真 SDK 接入后从安全卡读取实际容量；mock 期占位。
                SettingsInfoItem("存储使用", "-- / 32 GB")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 安全操作 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("安全操作")
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                SettingsActionItem(
                    icon = Icons.Default.Key,
                    title = "密钥更新",
                    subtitle = "替换文件加密的二级密钥",
                    iconTint = Accent,
                    onClick = { /* M7.4 */ }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.PhoneAndroid,
                    title = "设备绑定管理",
                    subtitle = "绑定/解绑本机",
                    iconTint = Accent,
                    onClick = { /* M7.3 */ }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.Timer,
                    title = "自动锁定",
                    subtitle = "后台 5 分钟无操作自动退出",
                    iconTint = Accent,
                    onClick = { /* M7.5 */ }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 危险操作 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("危险操作", color = Danger)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                SettingsActionItem(
                    icon = Icons.Default.DeleteForever,
                    title = "一键清理",
                    subtitle = "清除所有聊天记录和文件，保留登录态",
                    iconTint = Danger,
                    onClick = { showCleanDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.RestartAlt,
                    title = "恢复出厂",
                    subtitle = "擦除根密钥，还原初始状态",
                    iconTint = Danger,
                    onClick = { showResetDialog = true }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 退出登录：底部红色按钮，与上方卡片留出明显间距 ─────────────────────────
        Button(
            onClick = {
                deviceViewModel.logout()
                onLogoutComplete()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Danger)
        ) {
            Icon(Icons.Default.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("退出登录", fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // ── 关于：最底部小字链接 ──────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { showAboutDialog = true }) {
                Text(
                    "关于密盾 v1.0.0",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showCleanDialog) {
        val dismiss = {
            showCleanDialog = false
            cleanPassword = ""
            cleanError = ""
            cleanLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!cleanLoading) dismiss() },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("一键清理") },
            text = {
                Column {
                    Text(
                        "将清除安全卡中的所有聊天记录、隐私文件与联系人，保留登录态。\n\n此操作不可恢复，请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cleanPassword,
                        onValueChange = { cleanPassword = it; cleanError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = cleanError.isNotEmpty(),
                        supportingText = {
                            if (cleanError.isNotEmpty()) Text(cleanError, color = Danger)
                        },
                        enabled = !cleanLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        cleanLoading = true
                        cleanError = ""
                        deviceViewModel.wipeUserData(
                            password = cleanPassword,
                            onSuccess = { dismiss() },
                            onError = { msg ->
                                cleanError = msg
                                cleanLoading = false
                            }
                        )
                    },
                    enabled = cleanPassword.isNotBlank() && !cleanLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    if (cleanLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("清理中…")
                    } else {
                        Text("确认清除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !cleanLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showResetDialog) {
        val dismiss = {
            showResetDialog = false
            resetPassword = ""
            resetError = ""
            resetLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!resetLoading) dismiss() },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("恢复出厂设置", color = Danger) },
            text = {
                Column {
                    Text(
                        "此操作将永久删除安全卡内所有文件、聊天记录和密码，且无法恢复。\n\n请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetPassword,
                        onValueChange = { resetPassword = it; resetError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = resetError.isNotEmpty(),
                        supportingText = {
                            if (resetError.isNotEmpty()) Text(resetError, color = Danger)
                        },
                        enabled = !resetLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetLoading = true
                        resetError = ""
                        deviceViewModel.factoryReset(
                            password = resetPassword,
                            onSuccess = {
                                // 顺序与 [DeviceViewModel.wipeAndReset] / M3.5 一致：
                                // wipeAll 已完成才回调；这里再触发导航，避免本 VM 在 wipeAll 中途被 popUpTo(0) 取消。
                                dismiss()
                                onFactoryResetComplete()
                            },
                            onError = { msg ->
                                resetError = msg
                                resetLoading = false
                            }
                        )
                    },
                    enabled = resetPassword.isNotBlank() && !resetLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    if (resetLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("重置中…")
                    } else {
                        Text("确认重置")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !resetLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Security, null, tint = Primary) },
            title = { Text("密盾 v1.0.0") },
            text = {
                Column {
                    Text("USB安全卡管理系统")
                    Spacer(Modifier.height(8.dp))
                    Text("功能特性：", fontWeight = FontWeight.Medium)
                    Text("  - AES-256文件加密存储")
                    Text("  - ECDH端到端加密通信")
                    Text("  - SHA256密码保护")
                    Text("  - T620安全芯片")
                    Spacer(Modifier.height(8.dp))
                    Text("支持系统：Android 8.0+ / 鸿蒙4.0", fontSize = 12.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }
}

/** 分区标题。颜色默认用 TextSecondary，分区为危险/主色时按需覆盖。 */
@Composable
private fun SettingsSectionHeader(title: String, color: Color = TextSecondary) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 设备信息只读行：标签 + 值。 */
@Composable
private fun SettingsInfoItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 可点行：整行响应点击；ChevronRight 仅做视觉指示，非独立按钮。 */
@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = Primary,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
    }
}
