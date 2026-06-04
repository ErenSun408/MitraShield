package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel
import com.example.midun.viewmodel.DeviceViewModel
import com.example.midun.viewmodel.FileViewModel

@Composable
fun HomeScreen(
    onNavigateToFiles: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onQrCodeClick: () -> Unit = {},
    deviceViewModel: DeviceViewModel = hiltViewModel(),
    fileViewModel: FileViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    var showCleanDialog by remember { mutableStateOf(false) }

    // 进/返本屏重读单例，使其它 Tab 的增删（建文件夹、收消息等）即时反映到首页统计。
    // 三个 VM 均落在 Main 的 NavBackStackEntry scope，与各 Tab 同实例；底层 Mock 为 @Singleton。
    LaunchedEffect(Unit) {
        fileViewModel.loadFolders()
        chatViewModel.loadContacts()
    }
    val device by deviceViewModel.deviceStatus.collectAsState()
    val fileState by fileViewModel.uiState.collectAsState()
    val contacts by chatViewModel.contacts.collectAsState()

    val deviceConnected = device.status == UsbDeviceStatus.AUTHENTICATED ||
        device.status == UsbDeviceStatus.CONNECTED
    val folderCount = fileState.folders.size
    val fileCount = fileState.totalFileCount
    val unreadCount = contacts.sumOf { it.unreadCount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 设备状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Primary)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Accent, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("USB安全卡", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("SN: ${device.deviceId.ifEmpty { "未知" }}", color = Color.White.copy(0.7f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val statusColor = if (deviceConnected) Success else Danger
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(statusColor.copy(0.2f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (deviceConnected) "已连接" else "未连接",
                            color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Divider(color = Color.White.copy(0.15f))
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // 容量/已用空间 mock 期无数据源，占位（对齐 M7 设备信息策略）；M11 接 FSShell SDK 读真实卡容量。
                    StatusItem("存储容量", "-- / 32 GB")
                    StatusItem("已用空间", "--")
                    StatusItem("文件数量", "${fileCount}个")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 快捷功能
        Text("快捷功能", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionCard(
                icon = Icons.Default.FolderOpen,
                title = "隐私文件夹",
                subtitle = "${folderCount}个文件夹",
                color = Primary,
                onClick = onNavigateToFiles,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = Icons.Default.Chat,
                title = "即时通信",
                subtitle = if (unreadCount > 0) "${unreadCount}条新消息" else "暂无新消息",
                color = Accent,
                onClick = onNavigateToChat,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionCard(
                icon = Icons.Default.QrCode2,
                title = "扫码建链",
                subtitle = "安全连接",
                color = PrimaryLight,
                onClick = onQrCodeClick,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = Icons.Default.UsbOff,
                title = "一键清理",
                subtitle = "安全销毁",
                color = Danger,
                onClick = { showCleanDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // 安全日志
        Text("最近操作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        val logs = listOf(
            Triple("文件导入", "项目方案.pdf 导入至「工作文档」", "10:30"),
            Triple("即时通信", "与 张三 建立加密连接", "10:15"),
            Triple("文件导出", "会议纪要.docx 导出至手机", "09:45"),
            Triple("登录认证", "密码验证通过，设备ID匹配", "09:30"),
        )

        logs.forEach { (title, desc, time) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.History, null, tint = Primary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(desc, fontSize = 12.sp, color = TextSecondary)
                    }
                    Text(time, fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
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
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector, title: String, subtitle: String, color: Color,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        }
    }
}