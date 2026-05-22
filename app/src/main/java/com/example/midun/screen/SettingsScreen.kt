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
import com.example.midun.ui.theme.*

@Composable
fun SettingsScreen() {
    var showCleanDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var antiScreenshot by remember { mutableStateOf(true) }
    var autoLockMin by remember { mutableIntStateOf(5) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 设备信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Primary.copy(0.05f))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("设备信息", fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.height(12.dp))
                DeviceInfoRow("安全卡SN", "SC-2026051300001")
                DeviceInfoRow("手机设备ID", "MI-X8F2K9A3")
                DeviceInfoRow("绑定状态", "已绑定")
                DeviceInfoRow("固件版本", "v1.0.3")
                DeviceInfoRow("存储总容量", "16GB(明文) + 32GB(加密)")
                DeviceInfoRow("芯片型号", "T620")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 安全设置
        Text("安全设置", fontWeight = FontWeight.Bold, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ScreenLockPortrait, null, tint = Primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("防录屏", fontWeight = FontWeight.Medium)
                        Text("使用时禁止截屏和录屏", fontSize = 12.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = antiScreenshot,
                        onCheckedChange = { antiScreenshot = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Timer, null, tint = Primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动锁定", fontWeight = FontWeight.Medium)
                        Text("后台${autoLockMin}分钟无操作自动退出", fontSize = 12.sp, color = TextSecondary)
                    }
                    Text("${autoLockMin}分钟", color = Primary, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingItem(Icons.Default.Key, "密钥更新", "更新ECDH通信密钥") {}
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingItem(Icons.Default.PhoneAndroid, "设备绑定管理", "解绑/更换绑定设备") {}
            }
        }

        Spacer(Modifier.height(16.dp))

        // 危险操作
        Text("危险操作", fontWeight = FontWeight.Bold, color = Danger, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                SettingItem(Icons.Default.DeleteForever, "一键清理", "清除所有聊天记录和文件", Danger) {
                    showCleanDialog = true
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingItem(Icons.Default.RestartAlt, "恢复出厂", "擦除根密钥，还原初始状态", Danger) {
                    showResetDialog = true
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 关于
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            SettingItem(Icons.Default.Info, "关于密盾", "v1.0.0") {
                showAboutDialog = true
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // 一键清理对话框
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
                    Text("  - 操作日志", color = Danger)
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

    // 恢复出厂对话框
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
                    Text("  - 擦除flash中版本号", color = Danger)
                    Text("  - 还原为出厂初始状态", color = Danger)
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

@Composable
private fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingItem(
    icon: ImageVector, title: String, subtitle: String,
    iconTint: Color = Primary, onClick: () -> Unit = {}
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