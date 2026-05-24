package com.example.midun.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    var showMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    // 本地状态承载搜索词，避免经 ViewModel StateFlow 异步往返打断中文/IME 组合（见 M6.3 修复）。
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 搜索词非空时按内容/文件名过滤；空时用原始消息流。messages 变化时重算。
    val displayMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages
        else chatViewModel.searchMessages(contactId, searchQuery)
    }

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
                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索消息...", color = Color.White.copy(0.6f), fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(0.5f)
                            )
                        )
                    } else {
                        Column {
                            Text(contact?.remark ?: "聊天", fontSize = 16.sp)
                            Text(
                                "ECDH加密 · 设备ID: ${contact?.deviceId ?: ""}",
                                fontSize = 10.sp,
                                color = Color.White.copy(0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    if (showSearchBar) {
                        IconButton(onClick = { showSearchBar = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, "关闭搜索")
                        }
                    } else {
                        IconButton(onClick = { showBurnDialog = true }) {
                            Icon(Icons.Default.LocalFireDepartment, "阅后即焚", tint = Warning)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, "更多")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("搜索消息") },
                                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Primary) },
                                    onClick = { showSearchBar = true; showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("清空聊天记录", color = Danger) },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = Danger) },
                                    onClick = { showClearConfirm = true; showMenu = false }
                                )
                            }
                        }
                    }
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

            items(displayMessages, key = { it.id }) { msg ->
                ChatBubble(
                    msg = msg,
                    onDelete = { chatViewModel.deleteMessage(msg.id) },
                    onRecall = { chatViewModel.deleteMessage(msg.id) }
                )
            }

            if (searchQuery.isNotBlank() && displayMessages.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        Text("未找到匹配「$searchQuery」的消息", color = TextSecondary, fontSize = 13.sp)
                    }
                }
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

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("清空聊天记录") },
            text = { Text("将删除与该联系人的所有聊天记录，且无法恢复。", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        // 清完才返回：onBack 在 onComplete 里触发，避免提前销毁 scope 中断清除。
                        chatViewModel.clearAllMessages(contactId, onComplete = onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(msg: ChatMessage, onDelete: () -> Unit, onRecall: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
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
            Box {
                Card(
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (msg.isMine) 16.dp else 4.dp,
                        bottomEnd = if (msg.isMine) 4.dp else 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.isMine) ChatBubbleMine else ChatBubbleOther
                    ),
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true })
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
                // 微信式深色横排上下文菜单，锚定气泡下方，非全屏。
                if (showMenu) {
                    MessageActionMenu(
                        isMine = msg.isMine,
                        onDelete = onDelete,
                        onRecall = onRecall,
                        onDismiss = { showMenu = false }
                    )
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

/** 微信式深色横排长按菜单：锚定气泡下方（BottomStart），点外部/返回键关闭。 */
@Composable
private fun MessageActionMenu(
    isMine: Boolean,
    onDelete: () -> Unit,
    onRecall: () -> Unit,
    onDismiss: () -> Unit
) {
    // 把弹窗左上角放到气泡底边下方（+间隙），确保显示在消息下方而非覆盖其上。
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width)
                val y = anchorBounds.bottom + gapPx
                return IntOffset(x.coerceAtLeast(0), y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            color = Color(0xFF4C4C4C),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 6.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                MessageActionItem("删除", Icons.Default.Delete) { onDismiss(); onDelete() }
                if (isMine) {
                    MessageActionItem("撤回", Icons.Default.Undo) { onDismiss(); onRecall() }
                }
            }
        }
    }
}

@Composable
private fun MessageActionItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

private fun formatMessageTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
