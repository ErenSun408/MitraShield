package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.midun.data.*
import com.example.midun.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(contactId: String, onBack: () -> Unit) {
    val contact = MockData.contacts.find { it.id == contactId }
    val messages = MockData.chatMessages[contactId] ?: emptyList()
    var inputText by remember { mutableStateOf("") }
    var showBurnDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contact?.name ?: "聊天", fontSize = 16.sp)
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
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) {
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
                        onClick = { inputText = "" },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Send, "发送")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
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

            items(messages) { msg ->
                ChatBubble(msg)
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
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isFile = msg.type == MessageType.FILE
    val isImage = msg.type == MessageType.IMAGE
    val isVideo = msg.type == MessageType.VIDEO

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
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (isFile || isVideo) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isVideo) Icons.Default.VideoFile else Icons.Default.InsertDriveFile,
                                null,
                                tint = if (msg.isMine) Accent else Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                msg.content,
                                color = if (msg.isMine) Color.White else TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    } else if (isImage) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, null, tint = if (msg.isMine) Accent else Primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(msg.content, color = if (msg.isMine) Color.White else TextPrimary, fontSize = 14.sp)
                        }
                    } else {
                        Text(
                            msg.content,
                            color = if (msg.isMine) Color.White else TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = TextSecondary.copy(0.5f), modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(2.dp))
                Text(msg.time, fontSize = 10.sp, color = TextSecondary.copy(0.6f))
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