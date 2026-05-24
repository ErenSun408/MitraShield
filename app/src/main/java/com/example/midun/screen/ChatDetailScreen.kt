package com.example.midun.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.MessageType
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    contactId: String,
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val contacts by chatViewModel.contacts.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val contact = contacts.find { it.id == contactId }

    var inputText by remember { mutableStateOf("") }
    var showBurnDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()

    // 进会话：加载消息 + 清除未读（见 M6.1/M6.4 约定）。
    LaunchedEffect(contactId) {
        chatViewModel.loadMessages(contactId)
        chatViewModel.markRead(contactId)
    }

    // 自动滚到底：列表含 index 0 的加密横幅，故末条索引 = messages.size。
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contact?.remark ?: "聊天", fontSize = 16.sp)
                        Text(
                            "ECDH加密 · 设备ID: ${contact?.deviceId ?: ""}",
                            fontSize = 10.sp,
                            color = Color.White.copy(0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showBurnDialog = true }) {
                        Icon(Icons.Default.LocalFireDepartment, "阅后即焚", tint = Warning)
                    }
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    // edge-to-edge 下系统不再自动顶起布局，靠 imePadding 让输入栏随键盘上升、
                    // navigationBarsPadding 让其平时贴在系统导航栏之上（对齐 v4 §6.3）。
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        // Mock 发文件：真实文件选取器（隐私区/U盘/手机）按 patch 待实现第3条留到 M10。
                        chatViewModel.sendMessage("[文件] 示例文件.pdf", MessageType.FILE)
                    }) {
                        Icon(Icons.Default.AttachFile, "文件", tint = Primary)
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("输入消息...", fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(max = 120.dp),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                chatViewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Send, "发送")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("已建立端到端加密连接", fontSize = 11.sp, color = Accent)
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg = msg, onLongClick = { messageToDelete = msg })
            }
        }
    }

    if (showBurnDialog) {
        AlertDialog(
            onDismissRequest = { showBurnDialog = false },
            icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
            title = { Text("阅后即焚") },
            text = {
                Column {
                    Text("开启后，对方查看消息后将自动销毁")
                    Spacer(Modifier.height(12.dp))
                    listOf("5秒", "30秒", "1分钟", "5分钟").forEach { time ->
                        OutlinedButton(
                            onClick = { showBurnDialog = false },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) { Text(time) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBurnDialog = false }) { Text("关闭") }
            }
        )
    }

    messageToDelete?.let { msg ->
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("消息操作") },
            text = {
                Column {
                    TextButton(
                        onClick = { chatViewModel.deleteMessage(msg.id); messageToDelete = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Danger)
                        Spacer(Modifier.width(8.dp))
                        Text("删除（仅本端）", color = Danger)
                        Spacer(Modifier.weight(1f))
                    }
                    if (msg.isMine) {
                        TextButton(
                            onClick = { chatViewModel.deleteMessage(msg.id); messageToDelete = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Undo, null, tint = Warning)
                            Spacer(Modifier.width(8.dp))
                            Text("撤回（双向）", color = Warning)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { messageToDelete = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(msg: ChatMessage, onLongClick: () -> Unit) {
    val isFile = msg.type == MessageType.FILE
    val isImage = msg.type == MessageType.IMAGE
    val isVideo = msg.type == MessageType.VIDEO
    val isAudio = msg.type == MessageType.AUDIO

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
    ) {
        if (!msg.isMine) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(PrimaryLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start) {
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (msg.isMine) 16.dp else 4.dp,
                    bottomEnd = if (msg.isMine) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.isMine) ChatBubbleMine else ChatBubbleOther
                ),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
            ) {
                val contentColor = if (msg.isMine) Color.White else TextPrimary
                Column(modifier = Modifier.padding(12.dp)) {
                    when {
                        isFile || isVideo -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isVideo) Icons.Default.VideoFile else Icons.Default.InsertDriveFile,
                                null,
                                tint = if (msg.isMine) Accent else Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(msg.fileName ?: msg.content, color = contentColor, fontSize = 14.sp)
                                msg.fileSize?.let {
                                    Text(
                                        formatFileSize(it),
                                        color = contentColor.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        isImage -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, null, tint = if (msg.isMine) Accent else Primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(msg.content, color = contentColor, fontSize = 14.sp)
                        }
                        isAudio -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, null, tint = if (msg.isMine) Accent else Primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(msg.content.ifBlank { "语音消息" }, color = contentColor, fontSize = 14.sp)
                        }
                        else -> Text(msg.content, color = contentColor, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = TextSecondary.copy(0.5f), modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(2.dp))
                Text(formatMessageTime(msg.timestamp), fontSize = 10.sp, color = TextSecondary.copy(0.6f))
            }
        }

        if (msg.isMine) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatMessageTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
