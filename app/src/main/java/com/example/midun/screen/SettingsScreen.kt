package com.example.midun.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // M0 的清理/恢复确认弹框暂保留壳（弹完即关、无后端动作）；M7.2 替换为带密码框的真实版本。
    var showCleanDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
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

        Spacer(Modifier.height(16.dp))

        // ── 账户 ──────────────────────────────────────────────────────────────────
        SettingsSectionHeader("账户")
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            SettingsActionItem(
                icon = Icons.Default.Logout,
                title = "退出登录",
                subtitle = "清除认证状态，回到登录页",
                iconTint = TextSecondary,
                onClick = {
                    deviceViewModel.logout()
                    onLogoutComplete()
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 关于（M0 装饰，v4/patch 无；保留） ──────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            SettingsActionItem(
                icon = Icons.Default.Info,
                title = "关于密盾",
                subtitle = "v1.0.0",
                iconTint = Primary,
                onClick = { showAboutDialog = true }
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("一键清理") },
            text = {
                Column {
                    Text("此操作将清除USB安全卡中的：")
                    Spacer(Modifier.height(8.dp))
                    Text("  - 所有聊天记录", color = Danger)
                    Text("  - 所有隐私文件夹及文件", color = Danger)
                    Text("  - 所有联系人信息", color = Danger)
                    Spacer(Modifier.height(8.dp))
                    Text("此操作不可恢复！", fontWeight = FontWeight.Bold, color = Danger)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCleanDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("恢复出厂设置") },
            text = {
                Column {
                    Text("此操作将：")
                    Spacer(Modifier.height(8.dp))
                    Text("  - 擦除根密钥", color = Danger)
                    Text("  - 删除所有存储文件", color = Danger)
                    Text("  - 清除APP设置和登录密码", color = Danger)
                    Spacer(Modifier.height(8.dp))
                    Text("USB安全卡将需要重新初始化！", fontWeight = FontWeight.Bold, color = Danger)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
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

/** 可点行：图标 + 标题/副标题 + 右尾 ChevronRight。 */
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
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
        }
    }
}
