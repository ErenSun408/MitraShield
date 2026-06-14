package com.example.midun.screen

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.example.midun.data.FileCachePaths
import com.example.midun.data.model.ChatMessage
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.network.P2PSessionManager.ConnectionState
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    contactId: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val contacts by chatViewModel.contacts.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val contact = contacts.find { it.id == contactId }

    // 本会话是否已建立 P2P 连接（M10.6）：驱动顶部加密横幅 + 真实/离线提示。
    val connectionState by chatViewModel.connectionState.collectAsState()
    val activeContactId by chatViewModel.activeContactId.collectAsState()
    val connectedHere = connectionState == ConnectionState.CONNECTED && activeContactId == contactId

    // 阅后即焚模式（B 阶段）：驱动火苗图标高亮；开/关只在本会话已连接时可用。
    val burnMode by chatViewModel.burnMode.collectAsState()
    val burnOnHere = burnMode.enabled && connectedHere
    // 进行中的焚毁倒计时：messageId → 截止时刻，气泡据此显示剩余秒数（B 阶段）。
    val burnTimers by chatViewModel.burnTimers.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showBurnDialog by remember { mutableStateOf(false) }
    var showBurnGateDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    // 本地状态承载搜索词，避免经 ViewModel StateFlow 异步往返打断中文/IME 组合（见 M6.3 修复）。
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // —— 文件传输（M11.5.3）——
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val transferProgress by chatViewModel.transferProgress.collectAsState()
    val realCardMode by chatViewModel.realCardMode.collectAsState()   // 文件传输=真卡专属
    var fileToSave by remember { mutableStateOf<ChatMessage?>(null) } // 接收方点「保存」时选文件夹的目标消息
    var previewFile by remember { mutableStateOf<FileItem?>(null) }   // 预览中的文件（暂存区或已保存文件夹）
    var previewSaveTarget by remember { mutableStateOf<ChatMessage?>(null) } // 免保存预览时可「保存到文件夹」的接收消息
    // 打开媒体预览前先验卡内文件是否还在（缓存可能已被 7 天 TTL 清理）→ 过期/缺失优雅降级，不开空白预览。
    val openMediaPreview: (String, String, FileType, ChatMessage?) -> Unit = { path, name, type, saveTarget ->
        scope.launch {
            if (chatViewModel.cardFileExists(path)) {
                previewFile = FileItem(id = path, name = name, type = type)
                previewSaveTarget = saveTarget
            } else {
                snackbarHostState.showSnackbar(
                    if (FileCachePaths.isCachePath(path)) "缓存已过期，该文件已自动清理，无法预览"
                    else "文件不存在或已被删除，无法预览"
                )
            }
        }
    }
    var sendGateMsg by remember { mutableStateOf<String?>(null) }     // 不能发文件时的提示文案（非真卡/未连接）
    var cancelTarget by remember { mutableStateOf<ChatMessage?>(null) } // 取消在途发送的目标消息
    var showSourceMenu by remember { mutableStateOf(false) }          // 发送来源菜单（手机/隐私文件夹）
    var showPickDialog by remember { mutableStateOf(false) }          // 隐私文件夹来源选取对话框
    // 手机存储选取器（GetContent）：选中即查名/大小/mime → 流式加密发送。
    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = queryNameSize(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            chatViewModel.sendFile(
                name, size, mime,
                openStream = { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取所选文件") },
                onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
            )
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // 点标题进联系人资料页（改备注 / 删除联系人）。
                        Column(modifier = Modifier.clickable { onOpenProfile() }) {
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
                        IconButton(onClick = {
                            when {
                                !connectedHere -> showBurnGateDialog = true   // 未连接：提示先建联
                                burnMode.enabled -> chatViewModel.setBurnMode(false, 0) // 已开 → 直接关
                                else -> showBurnDialog = true                  // 未开 → 选时长开启
                            }
                        }) {
                            Icon(
                                Icons.Default.LocalFireDepartment,
                                if (burnOnHere) "关闭阅后即焚" else "阅后即焚",
                                tint = if (burnOnHere) Warning else Color.White
                            )
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
                    Box {
                        IconButton(onClick = {
                            // 文件传输=真卡专属（模拟模式收端无卡可落）；且需先建立连接（文件通道随会话建立）。
                            when {
                                !realCardMode -> sendGateMsg = "文件传输需在真卡模式下使用（当前为模拟模式）。"
                                !connectedHere -> sendGateMsg = "发送文件需先与对方建立加密连接（扫码或出码连接后再发送）。"
                                else -> showSourceMenu = true
                            }
                        }) {
                            val enabled = realCardMode && connectedHere
                            Icon(Icons.Default.AttachFile, "发送文件", tint = if (enabled) Primary else TextSecondary)
                        }
                        DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("从手机存储") },
                                leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, tint = Primary) },
                                onClick = { showSourceMenu = false; pickFileLauncher.launch("*/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("从隐私文件夹") },
                                leadingIcon = { Icon(Icons.Default.Folder, null, tint = Primary) },
                                onClick = {
                                    showSourceMenu = false
                                    showPickDialog = true
                                    chatViewModel.loadSaveFolders()
                                }
                            )
                        }
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
                            if (connectedHere) {
                                Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("已建立端到端加密连接", fontSize = 11.sp, color = Accent)
                            } else {
                                Icon(Icons.Default.LockOpen, null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("未连接 · 消息无法送达（需双方同时在线）", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }

            items(displayMessages, key = { it.id }) { msg ->
                when {
                    // 居中系统行：阅后即焚开/关提示（B 阶段）。
                    msg.type == MessageType.SYSTEM -> SystemLine(msg.content)
                    // 撤回墓碑（M10.5）：复用 SystemLine 居中渲染。
                    msg.recalled -> SystemLine(if (msg.isMine) "你撤回了一条消息" else "对方撤回了一条消息")
                    // 焚毁墓碑（B 阶段）：复用 SystemLine。
                    msg.burned -> SystemLine("🔥 阅后即焚消息已焚毁")
                    else -> ChatBubble(
                        msg = msg,
                        burnDeadline = burnTimers[msg.id],
                        transferFraction = transferProgress[msg.id],
                        onReveal = { chatViewModel.revealBurnMessage(msg.id, contactId, msg.burnTtl) },
                        onDelete = { chatViewModel.deleteMessage(msg.id) },
                        onRecall = { chatViewModel.recallMessage(msg.id) },
                        onFileTap = {
                            val transferring = transferProgress[msg.id] != null
                            val ft = fileTypeOf(msg.fileName ?: "")
                            val isMedia = ft == FileType.IMAGE || ft == FileType.VIDEO
                            when {
                                // 发送方点在途文件 → 取消发送确认。
                                msg.isMine && msg.type == MessageType.FILE && transferring -> cancelTarget = msg
                                // 发送方点自己发完的图/视频 → 预览卡内副本（手机来源 .sent_ / 隐私文件夹源路径）。
                                msg.isMine && msg.type == MessageType.FILE && !transferring &&
                                    isMedia && msg.localPath != null ->
                                    openMediaPreview(msg.localPath!!, msg.fileName ?: "", ft, null)
                                // 接收方收到、未保存：媒体免保存直接预览暂存区（点预览里再选保存）；非媒体走保存弹窗。
                                !msg.isMine && msg.type == MessageType.FILE && msg.savedFolderId == null &&
                                    msg.status == MessageStatus.RECEIVED && !transferring -> {
                                    if (isMedia) openMediaPreview(chatViewModel.stagingPathFor(msg.id), msg.fileName ?: "", ft, msg)
                                    else {
                                        fileToSave = msg
                                        chatViewModel.loadSaveFolders()
                                    }
                                }
                                // 已保存的图片/视频 → 预览（文件夹永久副本）。
                                msg.savedFolderId != null -> {
                                    if (isMedia) openMediaPreview("${msg.savedFolderId}/${msg.fileName}", msg.fileName ?: "", ft, null)
                                    else scope.launch { snackbarHostState.showSnackbar("已保存到文件夹，该类型暂不支持预览") }
                                }
                            }
                        }
                    )
                }
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
        // 时长选项（秒）：与 P2PSessionManager.formatTtl 对应。
        val options = listOf("5秒" to 5, "30秒" to 30, "1分钟" to 60, "5分钟" to 300)
        AlertDialog(
            onDismissRequest = { showBurnDialog = false },
            icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
            title = { Text("开启阅后即焚") },
            text = {
                Column {
                    Text("选择焚毁倒计时。开启后你发出的消息，对方读到后将在所选时长后于双方设备一并焚毁。")
                    Spacer(Modifier.height(12.dp))
                    options.forEach { (label, sec) ->
                        OutlinedButton(
                            onClick = {
                                chatViewModel.setBurnMode(true, sec)
                                showBurnDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBurnDialog = false }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    if (showBurnGateDialog) {
        AlertDialog(
            onDismissRequest = { showBurnGateDialog = false },
            icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
            title = { Text("阅后即焚") },
            text = { Text("需先与对方建立加密连接后才能开启阅后即焚。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showBurnGateDialog = false }) { Text("知道了", color = Primary) }
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

    // 不能发文件时的提示（非真卡 / 未连接，M11.5.3）。
    sendGateMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { sendGateMsg = null },
            icon = { Icon(Icons.Default.AttachFile, null, tint = Primary) },
            title = { Text("发送文件") },
            text = { Text(msg, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { sendGateMsg = null }) { Text("知道了", color = Primary) }
            }
        )
    }

    // 取消在途发送确认（M11.5.3 收尾）。
    cancelTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            icon = { Icon(Icons.Default.Cancel, null, tint = Danger) },
            title = { Text("取消发送") },
            text = { Text("确定取消发送「${msg.fileName ?: msg.content}」？已传输的部分会被对方丢弃。", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { chatViewModel.cancelFileSend(); cancelTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("取消发送") }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) { Text("继续发送", color = TextSecondary) }
            }
        )
    }

    // 接收文件「保存到隐私文件夹」对话框（M11.5.3）。
    fileToSave?.let { msg ->
        SaveToFolderDialog(
            fileName = msg.fileName ?: msg.content,
            folders = chatViewModel.saveFolders.collectAsState().value,
            onPickFolder = { folderId ->
                chatViewModel.saveReceivedFile(msg, folderId) { ok, message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
                fileToSave = null
            },
            onCreateFolder = { name ->
                chatViewModel.createFolderForSave(
                    name,
                    onCreated = { folderId ->
                        chatViewModel.saveReceivedFile(msg, folderId) { ok, message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                        fileToSave = null
                    },
                    onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                )
            },
            onDismiss = { fileToSave = null }
        )
    }

    // 隐私文件夹发送来源选取（M11.5.3b）：选文件夹 → 选文件 → 卡内流式加密发送。
    if (showPickDialog) {
        PickFromFolderDialog(
            folders = chatViewModel.saveFolders.collectAsState().value,
            files = chatViewModel.pickFiles.collectAsState().value,
            onOpenFolder = { chatViewModel.loadPickFiles(it) },
            onPickFile = { file ->
                chatViewModel.sendCardFile(file) { scope.launch { snackbarHostState.showSnackbar(it) } }
                showPickDialog = false
            },
            onDismiss = { showPickDialog = false }
        )
    }

    previewFile?.let { file ->
        FilePreviewDialog(
            file = file,
            onClose = { previewFile = null; previewSaveTarget = null },
            // 免保存预览（接收方未保存的媒体）时提供「保存到文件夹」→ 转交保存弹窗。
            onSave = previewSaveTarget?.let { m ->
                {
                    previewFile = null
                    previewSaveTarget = null
                    fileToSave = m
                    chatViewModel.loadSaveFolders()
                }
            }
        )
    }
}

/** 隐私文件夹发送来源选取对话框（M11.5.3b）：先列文件夹，进入后列文件，点文件即发送。 */
@Composable
private fun PickFromFolderDialog(
    folders: List<FileItem>,
    files: List<FileItem>,
    onOpenFolder: (folderId: String) -> Unit,
    onPickFile: (FileItem) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolder by remember { mutableStateOf<FileItem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Folder, null, tint = Primary) },
        title = { Text(selectedFolder?.name ?: "选择文件发送") },
        text = {
            Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                val folder = selectedFolder
                if (folder == null) {
                    if (folders.isEmpty()) {
                        Text("暂无隐私文件夹。", fontSize = 12.sp, color = TextSecondary)
                    } else {
                        folders.forEach { f ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { selectedFolder = f; onOpenFolder(f.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(f.name, fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    if (files.isEmpty()) {
                        Text("该文件夹暂无文件。", fontSize = 12.sp, color = TextSecondary)
                    } else {
                        files.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onPickFile(file) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(file.name, fontSize = 14.sp, maxLines = 1)
                                    Text(formatFileSize(file.size), fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (selectedFolder != null) selectedFolder = null else onDismiss() }) {
                Text(if (selectedFolder != null) "返回" else "取消", color = TextSecondary)
            }
        }
    )
}

/** 文件气泡内容（M11.5.3）：名/大小 + 进度条（传输中）/ 状态提示（待保存 / 已保存 / 失败）。 */
@Composable
private fun FileBubbleContent(msg: ChatMessage, transferFraction: Float?, contentColor: Color) {
    val ft = fileTypeOf(msg.fileName ?: "")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (ft) {
                    FileType.IMAGE -> Icons.Default.Image
                    FileType.VIDEO -> Icons.Default.VideoFile
                    FileType.AUDIO -> Icons.Default.AudioFile
                    else -> Icons.Default.InsertDriveFile
                },
                null,
                tint = if (msg.isMine) Accent else Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(msg.fileName ?: msg.content, color = contentColor, fontSize = 14.sp)
                msg.fileSize?.let {
                    Text(formatFileSize(it), color = contentColor.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }
        if (transferFraction != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { transferFraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (msg.isMine) Color.White else Primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${if (msg.isMine) "发送中" else "接收中"} ${(transferFraction * 100).toInt()}%",
                color = contentColor.copy(alpha = 0.7f), fontSize = 10.sp
            )
        } else {
            val isMedia = ft == FileType.IMAGE || ft == FileType.VIDEO
            val (hint, hintColor) = when {
                msg.status == MessageStatus.FAILED && !msg.isMine -> "接收失败" to Danger
                // 发送方：自己发的图/视频有卡内副本 → 可预览。
                msg.isMine && isMedia && msg.localPath != null && msg.status == MessageStatus.SENT ->
                    "👁 点击预览" to contentColor.copy(alpha = 0.7f)
                !msg.isMine && msg.savedFolderId == null ->
                    (if (isMedia) "👁 点击预览 · 可保存" else "📥 点击保存到文件夹") to Accent
                msg.savedFolderId != null ->
                    (if (isMedia) "✓ 已保存 · 点击预览" else "✓ 已保存到文件夹") to
                        contentColor.copy(alpha = 0.7f)
                else -> null to contentColor
            }
            hint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = hintColor, fontSize = 11.sp)
            }
        }
    }
}

/** 「保存到隐私文件夹」对话框：列已有文件夹 + 新建文件夹入口。 */
@Composable
private fun SaveToFolderDialog(
    fileName: String,
    folders: List<FileItem>,
    onPickFolder: (folderId: String) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FolderOpen, null, tint = Primary) },
        title = { Text("保存文件") },
        text = {
            Column {
                Text("将「$fileName」保存到隐私文件夹：", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新文件夹名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (folders.isEmpty()) {
                        Text("暂无文件夹，请新建一个。", fontSize = 12.sp, color = TextSecondary)
                    } else {
                        Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            folders.forEach { folder ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { onPickFolder(folder.id) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(folder.name, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.CreateNewFolder, null, tint = Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建文件夹", color = Primary)
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                Button(
                    onClick = { if (newName.isNotBlank()) onCreateFolder(newName.trim()) },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("创建并保存") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (creating) creating = false else onDismiss() }) {
                Text(if (creating) "返回" else "取消", color = TextSecondary)
            }
        }
    )
}

/** 按扩展名粗判文件类型（气泡图标 + 是否可预览）。 */
private fun fileTypeOf(name: String): FileType =
    when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> FileType.IMAGE
        "mp4", "mkv", "avi", "mov" -> FileType.VIDEO
        "mp3", "aac", "wav", "m4a" -> FileType.AUDIO
        "pdf", "doc", "docx", "txt", "xls", "xlsx" -> FileType.DOCUMENT
        else -> FileType.OTHER
    }

/** 从内容 URI 查显示名与大小（OpenableColumns）；查不到名用兜底、大小回 0。 */
private fun queryNameSize(context: android.content.Context, uri: android.net.Uri): Pair<String, Long> {
    var name = "未命名文件"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    }
    return name to size
}

/**
 * 居中灰色系统行：撤回墓碑、阅后即焚墓碑、开/关阅后即焚提示共用同一外观（B 阶段抽出）。
 * 只共享渲染，不改各自的数据语义（recalled/burned 仍是消息标记，SYSTEM 是独立事件行）。
 */
@Composable
private fun SystemLine(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 焚毁消息的状态标签（B 阶段）：已点开（burnDeadline 非空）显示每秒刷新的剩余倒计时；
 * 未点开时——发送方显示「对方读后焚毁」、接收方（理论上已被遮罩，不会走到此分支）显示静态提示。
 */
@Composable
private fun BurnStatusLabel(isMine: Boolean, burnDeadline: Long?, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocalFireDepartment, null, tint = Warning, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        if (burnDeadline != null) {
            var remaining by remember(burnDeadline) {
                mutableStateOf(((burnDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
            }
            LaunchedEffect(burnDeadline) {
                while (remaining > 0) {
                    delay(500)
                    remaining = ((burnDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                }
            }
            Text("${remaining}秒后焚毁", fontSize = 10.sp, color = contentColor.copy(alpha = 0.85f))
        } else {
            Text(
                if (isMine) "阅后即焚 · 对方读后焚毁" else "阅后即焚",
                fontSize = 10.sp,
                color = contentColor.copy(alpha = 0.85f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    msg: ChatMessage,
    burnDeadline: Long?,
    transferFraction: Float? = null,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
    onRecall: () -> Unit,
    onFileTap: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val isFile = msg.type == MessageType.FILE
    val isImage = msg.type == MessageType.IMAGE
    val isVideo = msg.type == MessageType.VIDEO
    val isAudio = msg.type == MessageType.AUDIO

    // 焚毁消息（B 阶段）：接收方未点开 → 遮罩；点开后 burnDeadline 非空 → 倒计时显示。
    val isBurn = msg.burnAfterRead
    val revealed = burnDeadline != null
    val masked = isBurn && !msg.isMine && !revealed

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
                    // 焚毁消息加火焰色描边以示特殊（B 阶段）。
                    border = if (isBurn) BorderStroke(1.dp, Warning) else null,
                    // 遮罩态点击揭示并启动倒计时；文件气泡点击触发保存/预览；其余点击无操作，长按弹菜单。
                    modifier = Modifier.combinedClickable(
                        onClick = { if (masked) onReveal() else if (isFile) onFileTap() },
                        onLongClick = { showMenu = true }
                    )
                ) {
                    val contentColor = if (msg.isMine) Color.White else TextPrimary
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (masked) {
                            // 遮罩：接收方点开前不显示原文（B 阶段）。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalFireDepartment, null, tint = Warning, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("点击查看", color = contentColor, fontSize = 14.sp)
                            }
                        } else {
                        when {
                            isFile -> FileBubbleContent(msg, transferFraction, contentColor)
                            isVideo -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.VideoFile, null,
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
                        // 焚毁状态标签：发送方/已揭示接收方显示（遮罩态不显示，B 阶段）。
                        if (isBurn) {
                            Spacer(Modifier.height(4.dp))
                            BurnStatusLabel(isMine = msg.isMine, burnDeadline = burnDeadline, contentColor = contentColor)
                        }
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
                // 自己发的且未送达：红色「未送达」提示（离线发送或连接已断，且不会在对方上线后补发）。
                if (msg.isMine && msg.status == MessageStatus.FAILED) {
                    Icon(Icons.Default.ErrorOutline, "未送达", tint = Danger, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("未送达", fontSize = 10.sp, color = Danger)
                    Spacer(Modifier.width(6.dp))
                }
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
