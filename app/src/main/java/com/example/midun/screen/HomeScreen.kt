package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.data.model.OperationType
import com.example.midun.data.model.SessionStatus
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel
import com.example.midun.util.formatStorage
import com.example.midun.viewmodel.DeviceViewModel
import com.example.midun.viewmodel.FileViewModel
import com.example.midun.viewmodel.OperationLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToFiles: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onQrCodeClick: () -> Unit = {},
    deviceViewModel: DeviceViewModel = hiltViewModel(),
    fileViewModel: FileViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    operationLogViewModel: OperationLogViewModel = hiltViewModel()
) {
    var showCleanDialog by remember { mutableStateOf(false) }
    var cleanPassword by remember { mutableStateOf("") }
    var cleanError by remember { mutableStateOf("") }
    var cleanLoading by remember { mutableStateOf(false) }

    // 进/返本屏重读单例，使其它 Tab 的增删（建文件夹、收消息等）即时反映到首页统计。
    // 三个 VM 均落在 Main 的 NavBackStackEntry scope，与各 Tab 同实例；底层 Mock 为 @Singleton。
    LaunchedEffect(Unit) {
        fileViewModel.loadFolders()
        chatViewModel.loadContacts()
    }
    val device by deviceViewModel.deviceStatus.collectAsState()
    val fileState by fileViewModel.uiState.collectAsState()
    val contacts by chatViewModel.contacts.collectAsState()
    val logs by operationLogViewModel.logs.collectAsState()

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
                        Text("守波秘钥", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("SN: ${device.deviceId.ifEmpty { "未知" }}", color = Color.White.copy(0.7f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Divider(color = Color.White.copy(0.15f))
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // 真卡容量（M11.6.1，SFGetCapacity）；未认证 totalBytes=0 → 显占位。
                    val total = device.totalBytes
                    val used = (device.totalBytes - device.freeBytes).coerceAtLeast(0L)
                    StatusItem("存储容量", if (total > 0) formatStorage(total) else "--")
                    StatusItem("已用空间", if (total > 0) formatStorage(used) else "--")
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
                title = "私藏清隅",
                color = Primary,
                onClick = onNavigateToFiles,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = Icons.Default.Chat,
                title = "相逢叙话",
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
                title = "同波相契",
                color = PrimaryLight,
                onClick = onQrCodeClick,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = Icons.Default.UsbOff,
                title = "涤净闲存",
                color = Danger,
                onClick = { showCleanDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // 最近操作：真实操作日志（OperationLogViewModel 观察 OperationLogRepository 的 StateFlow，
        // 各记录点经同一 @Singleton 写入，无需手动 reload）。清理类操作不记录自身（见 M9.3）。
        Text("最近操作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        val recentLogs = logs.take(MAX_HOME_LOGS)
        if (recentLogs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "暂无操作记录",
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            recentLogs.forEach { log ->
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
                            Icon(iconForOperation(log.type), null, tint = Primary, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(log.type.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(log.description, fontSize = 12.sp, color = TextSecondary)
                        }
                        Text(formatLogTime(log.timestamp), fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
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
            title = { Text("涤净闲存") },
            text = {
                Column {
                    Text(
                        "将清除设备中的所有聊天记录、文件、联系人与操作日志，保留登录态。\n\n此操作不可恢复，请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cleanPassword,
                        onValueChange = { cleanPassword = it; cleanError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
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
                            onSuccess = {
                                // wipeUserData 清 fileSystem + chatRepository 单例；刷新首页统计（文件夹/文件/未读归零）。
                                fileViewModel.loadFolders()
                                chatViewModel.loadContacts()
                                dismiss()
                            },
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
    icon: ImageVector, title: String, subtitle: String? = null, color: Color,
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
            // 副标题放标题右侧、底部对齐（而非另起一行）→ 有/无副标题的卡片同为「图标 + 单行文字」高度，四卡一致。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (subtitle != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val homeLogTimeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

private fun formatLogTime(timestamp: Long): String =
    homeLogTimeFormat.format(Date(timestamp))

private fun iconForOperation(type: OperationType): ImageVector = when (type) {
    OperationType.LOGIN -> Icons.Default.Lock
    OperationType.FILE_IMPORT -> Icons.Default.FileUpload
    OperationType.FILE_EXPORT -> Icons.Default.FileDownload
    OperationType.FILE_MOVE -> Icons.Default.DriveFileMove
    OperationType.FILE_DELETE -> Icons.Default.DeleteForever
    OperationType.CONNECT -> Icons.Default.Chat
}

private const val MAX_HOME_LOGS = 5