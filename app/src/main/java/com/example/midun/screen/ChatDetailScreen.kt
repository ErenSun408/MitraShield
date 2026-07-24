package com.example.midun.screen

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.example.midun.audio.VoicePlayer
import com.example.midun.audio.VoiceRecorder
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.midun.viewmodel.FileViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import java.util.Calendar
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
    onGoConnect: () -> Unit = {},
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
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val transferProgress by chatViewModel.transferProgress.collectAsState()
    var fileToSave by remember { mutableStateOf<ChatMessage?>(null) } // 接收方点「保存」时选文件夹的目标消息
    var previewFile by remember { mutableStateOf<FileItem?>(null) }   // 预览中的文件（暂存区或已保存文件夹）
    var previewSaveTarget by remember { mutableStateOf<ChatMessage?>(null) } // 免保存预览时可「保存到文件夹」的接收消息
    // 焚毁媒体预览关闭回调（阅后即焚）：图/视频退出预览时触发，登记倒计时。非焚毁预览为 null。
    var previewOnClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var burnDocCard by remember { mutableStateOf<ChatMessage?>(null) } // 焚毁文档只读卡片（文档不支持预览）的目标消息
    // 打开媒体预览前先验卡内文件是否还在（缓存可能已被 7 天 TTL 清理）→ 过期/缺失优雅降级，不开空白预览。
    // [onClose] 非空 = 焚毁媒体：退出预览时触发（登记焚毁倒计时）；普通预览传 null。
    val openMediaPreview: (String, String, FileType, ChatMessage?, (() -> Unit)?) -> Unit = { path, name, type, saveTarget, onClose ->
        scope.launch {
            if (chatViewModel.cardFileExists(path)) {
                previewFile = FileItem(id = path, name = name, type = type)
                previewSaveTarget = saveTarget
                previewOnClose = onClose
            } else {
                snackbarHostState.showSnackbar(
                    if (FileCachePaths.isCachePath(path)) "缓存已过期，该文件已自动清理，无法预览"
                    else "文件不存在或已被删除，无法预览"
                )
            }
        }
    }
    var cancelTarget by remember { mutableStateOf<ChatMessage?>(null) } // 取消在途发送的目标消息
    var resendTarget by remember { mutableStateOf<ChatMessage?>(null) } // 点「未送达」重发的目标消息
    var showSourceMenu by remember { mutableStateOf(false) }          // 发送来源菜单（手机/文件夹）
    var showPickDialog by remember { mutableStateOf(false) }          // 文件夹来源选取对话框
    var showPlusPanel by remember { mutableStateOf(false) }           // 微信式 + 工具栏（文件 / 拍摄）
    var showCamera by remember { mutableStateOf(false) }              // 全屏拍摄页（微信式即拍即发）
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

    // 拍摄即发（微信式）：临时文件（App 私有 cacheDir）流式加密发送后即删，不留手机明文。
    fun sendCaptured(file: java.io.File, mime: String) {
        val name = if (mime.startsWith("video/")) "拍摄_${file.name.substringAfter('_')}"
            else "拍照_${file.name.substringAfter('_')}"
        chatViewModel.sendFile(
            name, file.length(), mime,
            openStream = { file.inputStream() },
            onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
            onComplete = { runCatching { file.delete() } } // 发完删临时明文（发送方预览副本已加密落卡）
        )
    }

    // —— 语音消息（[chat-voice]，按住说话）——
    val voiceRecorder = remember { VoiceRecorder(context) }
    val voicePlayer = remember { VoicePlayer() }
    var voiceMode by remember { mutableStateOf(false) }              // true=语音输入态，false=文字输入
    // 切回键盘模式时自动弹键盘（对称于切到语音模式时键盘自动下落）：toggle 置 pending，TextField 重入组合后 requestFocus。
    val inputFocusRequester = remember { FocusRequester() }
    var pendingKeyboardFocus by remember { mutableStateOf(false) }
    LaunchedEffect(voiceMode, pendingKeyboardFocus) {
        if (!voiceMode && pendingKeyboardFocus) {
            runCatching { inputFocusRequester.requestFocus() } // 聚焦文字框即弹起软键盘
            pendingKeyboardFocus = false
        }
    }
    var recording by remember { mutableStateOf(false) }              // 正在录音
    var cancelArmed by remember { mutableStateOf(false) }            // 上滑进入取消区（松手则取消）
    var recordSeconds by remember { mutableIntStateOf(0) }           // 录音时长（按钮内实时显示）
    var playingVoiceId by remember { mutableStateOf<String?>(null) } // 正在播放的语音消息 id
    // 阅后即焚语音：已点开（揭示+播放）但**计时尚未起**的消息 id。焚毁计时改在「听完」(播放完成) 才启动，
    // 故「已揭示」要和「计时器已起(burnTimers)」解耦——这个本地集合承载「已揭示、播放中、还没听完」这段。
    var openedBurnVoice by remember { mutableStateOf(setOf<String>()) }
    val hasMicPermission = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbarHostState.showSnackbar("需要麦克风权限才能发送语音") }
    }
    // 录音计时（按钮内显示秒数）。
    LaunchedEffect(recording) {
        while (recording) { recordSeconds = voiceRecorder.elapsedSec(); delay(200) }
    }
    // 离开会话页：停播放、弃在录的音（避免泄漏 MediaRecorder/Player 与临时文件）。
    DisposableEffect(Unit) { onDispose { voicePlayer.stop(); voiceRecorder.cancel() } }

    // 搜索词非空时按内容/文件名过滤；空时用原始消息流。messages 变化时重算。
    val displayMessages = remember(messages, searchQuery) {
        val base = if (searchQuery.isBlank()) messages
            else chatViewModel.searchMessages(contactId, searchQuery)
        // 焚毁后的消息不再显示（去掉焚毁墓碑「🔥 阅后即焚消息已焚毁」，阅后不留痕）。
        base.filterNot { it.burned }
    }

    // 进会话：加载消息 + 清除未读（见 M6.1/M6.4 约定）。
    LaunchedEffect(contactId) {
        chatViewModel.loadMessages(contactId)
        chatViewModel.markRead(contactId)
    }

    // 自动滚到底：列表首项即首条消息（加密横幅已挪进顶栏），故末条索引 = 条数 - 1。
    // 首次进会话**瞬时**跳到底（避免先停在最旧消息、再花约 1s 动画滚下来）；之后新消息才平滑滚动。
    // remember(contactId)：切换联系人时重置，使每个新打开的会话都先瞬时定位。
    var didInitialScroll by remember(contactId) { mutableStateOf(false) }
    // 同时以末条消息 id 为 key：重发会「删旧+插新」使 size 不变，但末条变化仍需滚到底。
    // 键仍取原始 messages：搜索过滤只改可见条数，不该触发「跳到底」。
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (displayMessages.isNotEmpty()) {
            val last = displayMessages.size - 1
            if (!didInitialScroll) {
                listState.scrollToItem(last)
                didInitialScroll = true
            } else {
                listState.animateScrollToItem(last)
            }
        }
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
                        // 顶栏三段式：左=联系人名+已加密（点进资料页，位置不变）；正中=放大的连接状态（客户要突出）；
                        // 右=actions 图标。用 Box 让连接状态在顶栏内水平居中，与两侧并排、不堆成多行，故无需增高。
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // 左侧：联系人名 + 「已加密」小字（都不动）。名过长时单行截断，避免换行撑高 / 挤占中间。
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .clickable { onOpenProfile() }
                            ) {
                                Text(contact?.remark ?: "聊天", fontSize = 16.sp, maxLines = 1)
                                Text("已加密", fontSize = 10.sp, color = Color.White.copy(0.7f))
                            }
                            // 正中：放大的连接状态；未连接时其下挂「前往建立连接」副标题（字号比已加密大）。
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    if (connectedHere) "已连接" else "未连接",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (connectedHere) Accent else Danger
                                )
                                // 点建连链接触发 onGoConnect（内层 clickable 消费事件，不冒泡到进资料页）。
                                if (!connectedHere) {
                                    Text(
                                        "前往建立连接",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable { onGoConnect() }
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
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
                    // 微信式布局：最左语音/键盘切换 → 中间输入框/按住说话 → 最右文件(空)/发送(有内容,渐变)。
                    IconButton(onClick = {
                        if (voiceMode) pendingKeyboardFocus = true // 语音→键盘：切换后自动弹起键盘
                        voiceMode = !voiceMode
                        if (voiceMode) voicePlayer.stop()
                    }) {
                        Icon(
                            if (voiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                            if (voiceMode) "切换键盘" else "切换语音",
                            tint = Primary
                        )
                    }
                    if (voiceMode) {
                        // 按住说话（微信式）：按下开始录音、松手发送、上滑取消。录音/取消反馈直接显示在按钮内。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (recording && !cancelArmed) PrimaryLight else Surface)
                                .pointerInput(voiceMode) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        if (!hasMicPermission()) {
                                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                                            return@awaitEachGesture
                                        }
                                        if (!voiceRecorder.start()) {
                                            scope.launch { snackbarHostState.showSnackbar("无法开始录音") }
                                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                                            return@awaitEachGesture
                                        }
                                        recording = true; cancelArmed = false; recordSeconds = 0
                                        var cancel = false
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: event.changes.first()
                                                cancel = (change.position.y - down.position.y) < -120f
                                                cancelArmed = cancel
                                                if (!change.pressed) break
                                            }
                                        } finally {
                                            recording = false; cancelArmed = false
                                            if (cancel) {
                                                voiceRecorder.cancel()
                                            } else {
                                                val rec = voiceRecorder.stop()
                                                if (rec != null) chatViewModel.sendVoice(rec.file, rec.durationSec) {
                                                    scope.launch { snackbarHostState.showSnackbar(it) }
                                                } else scope.launch { snackbarHostState.showSnackbar("说话时间太短") }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when {
                                    recording && cancelArmed -> "松开手指 · 取消发送"
                                    recording -> "● ${recordSeconds}″   松开发送 · 上滑取消"
                                    else -> "按住 说话"
                                },
                                fontSize = 14.sp,
                                color = if (cancelArmed) Danger else TextPrimary
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("输入消息...", fontSize = 14.sp) },
                            modifier = Modifier.weight(1f).heightIn(max = 120.dp).focusRequester(inputFocusRequester),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 最右槽位：输入框为空(或语音模式)→ 文件图标(开来源菜单)；有内容 → 渐变为发送按钮。
                    Box {
                        Crossfade(
                            targetState = !voiceMode && inputText.isNotBlank(),
                            label = "sendOrAttach"
                        ) { showSend ->
                            if (showSend) {
                                FilledIconButton(
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            chatViewModel.sendMessage(inputText.trim())
                                            inputText = ""
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)
                                ) {
                                    Icon(Icons.Default.Send, "发送")
                                }
                            } else {
                                // 微信式 ⊕：点开工具栏（文件 / 拍摄）。开面板先收键盘，避免面板与键盘打架。
                                IconButton(onClick = {
                                    focusManager.clearFocus()
                                    showSourceMenu = false
                                    showPlusPanel = !showPlusPanel
                                }) {
                                    Icon(Icons.Default.AddCircleOutline, "更多", tint = Primary)
                                }
                            }
                        }
                        // 「文件」子来源菜单（手机 / 文件夹），由工具栏「文件」格触发。
                        DropdownMenu(
                            expanded = showSourceMenu,
                            onDismissRequest = { showSourceMenu = false },
                            properties = PopupProperties(focusable = false)
                        ) {
                            DropdownMenuItem(
                                text = { Text("从手机存储") },
                                leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, tint = Primary) },
                                onClick = { showSourceMenu = false; pickFileLauncher.launch("*/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("从文件夹") },
                                leadingIcon = { Icon(Icons.Default.Folder, null, tint = Primary) },
                                onClick = {
                                    showSourceMenu = false
                                    showPickDialog = true
                                    chatViewModel.loadSaveFolders()
                                }
                            )
                        }
                    }
                }
                // 微信式 + 工具栏：输入栏下方滑出的格子面板（第一格文件、第二格拍摄）。
                PlusToolPanel(
                    visible = showPlusPanel,
                    onFile = { showPlusPanel = false; showSourceMenu = true },
                    onCamera = { showPlusPanel = false; showCamera = true }
                )
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
            itemsIndexed(displayMessages, key = { _, m -> m.id }) { index, msg ->
                // 微信式时间分隔行：第一条前必显；与上一条间隔 > 5 分钟才显（智能日期格式）。
                val prev = displayMessages.getOrNull(index - 1)
                if (prev == null || msg.timestamp - prev.timestamp > TIME_SEPARATOR_GAP_MS) {
                    TimeSeparator(msg.timestamp)
                }
                when {
                    // 「去建立连接」系统提示行（带可点链接）：未建立会话发消息后插入。
                    msg.type == MessageType.SYSTEM && msg.connectPrompt ->
                        ConnectPromptLine(text = msg.content, onGoConnect = onGoConnect)
                    // 居中系统行：阅后即焚开/关提示（B 阶段）。
                    msg.type == MessageType.SYSTEM -> SystemLine(msg.content)
                    // 撤回墓碑（M10.5）：复用 SystemLine 居中渲染。
                    msg.recalled -> SystemLine(if (msg.isMine) "你撤回了一条消息" else "对方撤回了一条消息")
                    // 焚毁后的消息已在 displayMessages 过滤掉（不再显示墓碑）。
                    else -> ChatBubble(
                        msg = msg,
                        burnDeadline = burnTimers[msg.id],
                        transferFraction = transferProgress[msg.id],
                        onReveal = { chatViewModel.revealBurnMessage(msg.id, contactId, msg.burnTtl) },
                        onDelete = { chatViewModel.deleteMessage(msg.id) },
                        onRecall = { chatViewModel.recallMessage(msg.id) },
                        onRecallBlocked = {
                            scope.launch { snackbarHostState.showSnackbar("发送超过两分钟的消息不支持撤回") }
                        },
                        onResend = { resendTarget = msg },
                        audioPlaying = playingVoiceId == msg.id,
                        audioBurnOpened = msg.id in openedBurnVoice,
                        onAudioTap = {
                            if (playingVoiceId == msg.id) {
                                voicePlayer.stop(); playingVoiceId = null
                            } else scope.launch {
                                val bytes = chatViewModel.readVoiceBytes(msg)
                                if (bytes == null) {
                                    snackbarHostState.showSnackbar("语音不可用，可能已过期")
                                    return@launch
                                }
                                // 焚毁语音：首次点开记入 openedBurnVoice（揭示遮罩）；计时**不在此刻起**。
                                val burnRecv = msg.burnAfterRead && !msg.isMine
                                if (burnRecv) openedBurnVoice = openedBurnVoice + msg.id
                                val tmp = File(context.cacheDir, "voice_play.m4a")
                                tmp.writeBytes(bytes)
                                playingVoiceId = msg.id
                                // 播放完成回调：普通语音仅复位；焚毁语音在**听完**才启动 ttl 倒计时（到点两端焚）。
                                val started = voicePlayer.play(tmp) {
                                    playingVoiceId = null
                                    if (burnRecv) chatViewModel.revealBurnMessage(msg.id, contactId, msg.burnTtl)
                                }
                                if (!started) {
                                    playingVoiceId = null
                                    snackbarHostState.showSnackbar("语音播放失败")
                                }
                            }
                        },
                        onFileTap = {
                            val transferring = transferProgress[msg.id] != null
                            val ft = fileTypeOf(msg.fileName ?: "")
                            val isMedia = ft == FileType.IMAGE || ft == FileType.VIDEO
                            // 接收方焚毁文件（[chat-voice] 之外的图/视频/文档焚毁）：一次性预览，退出预览才起倒计时。
                            val burnRecv = msg.burnAfterRead && !msg.isMine && msg.type == MessageType.FILE
                            when {
                                // 焚毁文件二次预览拦截：已看过并退出过一次（已登记倒计时）→ 提示不支持二次预览。
                                burnRecv && burnTimers[msg.id] != null -> scope.launch {
                                    snackbarHostState.showSnackbar("阅后即焚文件不支持二次预览")
                                }
                                // 焚毁图/视频首次预览：无「保存到文件夹」按钮（saveTarget=null），退出预览时登记焚毁倒计时。
                                burnRecv && isMedia -> openMediaPreview(
                                    chatViewModel.stagingPathFor(msg.id), msg.fileName ?: "", ft, null
                                ) { chatViewModel.revealBurnMessage(msg.id, contactId, msg.burnTtl) }
                                // 焚毁文档/其他（不支持预览）：弹只读卡片（名+大小），关闭卡片时登记焚毁倒计时。
                                burnRecv -> burnDocCard = msg
                                // 发送方点在途文件 → 取消发送确认。
                                msg.isMine && msg.type == MessageType.FILE && transferring -> cancelTarget = msg
                                // 发送方点自己发完的图/视频 → 预览卡内副本（手机来源 .sent_ / 隐私文件夹源路径）。
                                msg.isMine && msg.type == MessageType.FILE && !transferring &&
                                    isMedia && msg.localPath != null ->
                                    openMediaPreview(msg.localPath!!, msg.fileName ?: "", ft, null, null)
                                // 接收方收到、未保存：媒体免保存直接预览暂存区（点预览里再选保存）；非媒体走保存弹窗。
                                !msg.isMine && msg.type == MessageType.FILE && msg.savedFolderId == null &&
                                    msg.status == MessageStatus.RECEIVED && !transferring -> {
                                    if (isMedia) openMediaPreview(
                                        chatViewModel.stagingPathFor(msg.id), msg.fileName ?: "", ft, msg, null
                                    )
                                    else {
                                        fileToSave = msg
                                        chatViewModel.loadSaveFolders()
                                    }
                                }
                                // 已保存的图片/视频 → 预览（文件夹永久副本）。
                                msg.savedFolderId != null -> {
                                    if (isMedia) openMediaPreview("${msg.savedFolderId}/${msg.fileName}", msg.fileName ?: "", ft, null, null)
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
        // 读后倒计时（秒）：对端读到后双端同焚。发送方自己那份不设倒计时——登录时统一清除，对用户无感知。
        val readOptions = listOf("10秒" to 10, "15秒" to 15, "30秒" to 30, "1分钟" to 60)
        var readSec by remember { mutableStateOf(30) }
        AlertDialog(
            onDismissRequest = { showBurnDialog = false },
            icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
            title = { Text("开启阅后即焚") },
            text = {
                BurnDurationSection(
                    title = "读后倒计时",
                    subtitle = "对方读到后，消息在所选时长后于双方设备一并焚毁。",
                    options = readOptions,
                    selected = readSec,
                    onSelect = { readSec = it }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.setBurnMode(true, readSec)
                    showBurnDialog = false
                }) { Text("开启", color = Primary) }
            },
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

    // 「未送达」重发确认（微信式）。无会话时重发也只会再失败、提示行已在底部说明。
    resendTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { resendTarget = null },
            icon = { Icon(Icons.Default.Refresh, null, tint = Primary) },
            title = { Text("重发消息") },
            text = {
                Text(
                    "是否重新发送该消息？",
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.resendMessage(msg) { scope.launch { snackbarHostState.showSnackbar(it) } }
                        resendTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("重发") }
            },
            dismissButton = {
                TextButton(onClick = { resendTarget = null }) { Text("取消", color = TextSecondary) }
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
            loading = chatViewModel.pickLoading.collectAsState().value,
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
            // 焚毁媒体：退出预览触发 previewOnClose（登记倒计时）。普通预览该回调为 null。
            onClose = {
                previewOnClose?.invoke()
                previewFile = null; previewSaveTarget = null; previewOnClose = null
            },
            // 免保存预览（接收方未保存的媒体）时提供「保存到文件夹」→ 转交保存弹窗。焚毁媒体 saveTarget=null → 不显示。
            onSave = previewSaveTarget?.let { m ->
                {
                    previewFile = null
                    previewSaveTarget = null
                    previewOnClose = null
                    fileToSave = m
                    chatViewModel.loadSaveFolders()
                }
            }
        )
    }

    // 焚毁文档只读卡片（文档不支持预览）：显示名+大小，关闭时登记焚毁倒计时（退出即开始）。
    burnDocCard?.let { msg ->
        BurnDocDialog(
            fileName = msg.fileName ?: msg.content,
            fileSize = msg.fileSize,
            onDismiss = {
                chatViewModel.revealBurnMessage(msg.id, contactId, msg.burnTtl)
                burnDocCard = null
            }
        )
    }

    // 全屏拍摄页（微信式即拍即发）：盖在会话之上。拍完即发+退出；点右上 X 退出。
    if (showCamera) {
        CameraCaptureScreen(
            onCaptured = { file, mime -> showCamera = false; sendCaptured(file, mime) },
            onExit = { showCamera = false }
        )
    }
}

/**
 * 微信式 + 工具栏：输入栏下方滑出的格子面板。当前两格——「文件」（复用手机/文件夹来源菜单）、「拍摄」（相机页）。
 * 各格 = 圆角图标 + 文字，横向排列；后续加"相册"等只需再加一格。
 */
@Composable
private fun PlusToolPanel(
    visible: Boolean,
    onFile: () -> Unit,
    onCamera: () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.width(8.dp))
            PlusTool(Icons.Default.InsertDriveFile, "文件", onFile)
            PlusTool(Icons.Default.PhotoCamera, "拍摄", onCamera)
        }
    }
}

/** + 工具栏单格：圆角浅底图标 + 下方文字标签。 */
@Composable
private fun PlusTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = TextSecondary)
    }
}

/**
 * 阅后即焚弹框里的时长选择（标题 + 说明 + 一排单选时长）。
 * 自绘选择块而非 FilterChip：等宽平分一行，「10分钟」这类长标签在窄块里也不会被裁。
 */
@Composable
private fun BurnDurationSection(
    title: String,
    subtitle: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, sec) ->
                val isSelected = sec == selected
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(sec) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Warning.copy(alpha = 0.18f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isSelected) Warning else TextSecondary.copy(alpha = 0.4f))
                ) {
                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = if (isSelected) Warning else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 焚毁文档只读卡片：文档不支持预览（见 FilePreviewDialog），焚毁文档点开显示名称+大小的确认卡片，
 * 不提供预览/下载/保存（阅后即焚语义）。关闭本卡片（`onDismiss`）即视为「看过一次」→ 由调用方登记焚毁倒计时。
 */
@Composable
private fun BurnDocDialog(fileName: String, fileSize: Long?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
        title = { Text(fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                fileSize?.let { Text(formatFileSize(it), color = TextSecondary, fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "阅后即焚文档不支持预览与保存。关闭本窗口后将开始焚毁倒计时，且不可再次查看。",
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了", color = Primary) }
        }
    )
}

/** 隐私文件夹发送来源选取对话框（M11.5.3b）：先列文件夹，进入后列文件，点文件即发送。 */
@Composable
private fun PickFromFolderDialog(
    folders: List<FileItem>,
    files: List<FileItem>,
    loading: Boolean,
    onOpenFolder: (folderId: String) -> Unit,
    onPickFile: (FileItem) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolder by remember { mutableStateOf<FileItem?>(null) }
    // 缩略图复用文件列表逻辑（FileViewModel.loadThumbnail：图片解密下采样、视频提首帧、LruCache 缓存）。
    // dialog 按需组合 → 仅打开选取时创建该 VM（同 ChatDetail entry 单例，init 多读一次文件夹列表，无害）。
    val fileVm: FileViewModel = hiltViewModel()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Folder, null, tint = Primary) },
        title = { Text(selectedFolder?.name ?: "选择文件发送") },
        text = {
            // 固定宽高：弹框尺寸不随状态或名称长短跳动；内容超高时内部滚动。加载中留空、框体不动。
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val folder = selectedFolder
                when {
                    folder == null && folders.isEmpty() ->
                        PickEmptyState(Icons.Default.FolderOff, "暂无文件夹")
                    folder == null ->
                        folders.forEach { f ->
                            PickRow(
                                icon = Icons.Default.Folder,
                                iconTint = Primary,
                                title = f.name,
                                subtitle = null,
                                trailing = Icons.Default.ChevronRight,
                                onClick = { selectedFolder = f; onOpenFolder(f.id) }
                            )
                        }
                    // 加载中：留空（min 高度占位），读完即填；不显「暂无文件」避免误闪。
                    loading -> Unit
                    files.isEmpty() ->
                        PickEmptyState(Icons.Default.InsertDriveFile, "该文件夹暂无文件")
                    else ->
                        files.forEach { file ->
                            val (ic, tint) = fileIconOf(file.name)
                            // 图片/视频显示卡内解密缩略图（与文件列表一致）；未就绪/失败退回类型图标。
                            val previewable = file.type == FileType.IMAGE || file.type == FileType.VIDEO
                            var thumb by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
                            if (previewable) {
                                LaunchedEffect(file.id) { thumb = fileVm.loadThumbnail(file) }
                            }
                            PickRow(
                                icon = ic,
                                iconTint = tint,
                                thumbnail = thumb,
                                isVideo = file.type == FileType.VIDEO,
                                title = file.name,
                                subtitle = formatFileSize(file.size),
                                trailing = Icons.Default.Send,
                                onClick = { onPickFile(file) }
                            )
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

/** 选取对话框的一行（文件夹/文件通用）：圆角卡片 + 着色图标块 + 标题/副标题 + 尾部图标。 */
@Composable
private fun PickRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String?,
    trailing: ImageVector,
    onClick: () -> Unit,
    thumbnail: ImageBitmap? = null,
    isVideo: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (isVideo) { // 视频右下角叠播放三角（与文件列表一致）
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(1.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(10.dp))
                        }
                    }
                } else {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(trailing, null, tint = TextSecondary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

/** 选取对话框的空态（无文件夹/空文件夹）：居中图标 + 文案，带上下留白避免贴边。 */
@Composable
private fun PickEmptyState(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 12.sp, color = TextSecondary)
    }
}

/** 按文件类型给选取行的图标与着色。 */
private fun fileIconOf(name: String): Pair<ImageVector, Color> =
    when (fileTypeOf(name)) {
        FileType.IMAGE -> Icons.Default.Image to Accent
        FileType.VIDEO -> Icons.Default.VideoFile to Warning
        FileType.AUDIO -> Icons.Default.AudioFile to Success
        FileType.DOCUMENT -> Icons.Default.Description to Primary
        else -> Icons.Default.InsertDriveFile to TextSecondary
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
                Text(
                    msg.fileName ?: msg.content,
                    color = contentColor,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
                // 焚毁文件（接收方）：不显「点击预览/保存」提示——焚毁文件不可保存、且看过一次即不可再看，
                // 状态由火焰描边 + 「N秒后焚毁」标签承载（BurnStatusLabel）。
                msg.burnAfterRead && !msg.isMine -> null to contentColor
                // 发送方自己发的图/视频可点击预览（功能保留），但不再显文字提示。
                msg.isMine -> null to contentColor
                !msg.isMine && msg.savedFolderId == null ->
                    (if (isMedia) "点击预览 · 可保存" else "📥 点击保存到文件夹") to Accent
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
                Text("将「$fileName」保存到文件夹：", fontSize = 13.sp, color = TextSecondary)
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

/** 按扩展名粗判文件类型（气泡图标 + 是否可预览）。聊天文件无落卡文件头 → 用健壮扩展名解析（剥 `(3)` 等尾巴）。 */
private fun fileTypeOf(name: String): FileType = com.example.midun.data.FileTypes.fromExtension(name)

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
 * 「去建立连接」系统提示行：未建立会话发消息后插入。居中灰字 + 末尾可点蓝色链接，点击跳扫码/出码连接页。
 * 作为历史系统消息保留（重连后不删）。
 */
@Composable
private fun ConnectPromptLine(text: String, onGoConnect: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$text · ", fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
            Text(
                "去建立连接",
                fontSize = 11.sp,
                color = Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onGoConnect() }
            )
        }
    }
}

/**
 * 焚毁消息的状态标签（B 阶段）：接收方点开后有死线（burnDeadline 非空）→ 显示每秒刷新的读后倒计时。
 * 发送方自己那份不设倒计时（登录时统一清除）→ 无死线，显示「对方读后焚毁」；接收方未点开（已被遮罩挡住）
 * 同样走无死线文案。
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
            Text(formatBurnRemaining(remaining), fontSize = 10.sp, color = contentColor.copy(alpha = 0.85f))
        } else {
            Text(
                if (isMine) "阅后即焚 · 对方读后焚毁" else "阅后即焚",
                fontSize = 10.sp,
                color = contentColor.copy(alpha = 0.85f)
            )
        }
    }
}

/** 焚毁剩余时长文案：读后倒计时最长 1 分钟，按秒/分显示。 */
private fun formatBurnRemaining(seconds: Long): String = when {
    seconds < 60 -> "${seconds}秒后焚毁"
    seconds % 60 == 0L -> "${seconds / 60}分钟后焚毁"
    else -> "${seconds / 60}分${seconds % 60}秒后焚毁"
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
    onRecallBlocked: () -> Unit = {},
    onResend: () -> Unit = {},
    onFileTap: () -> Unit = {},
    onAudioTap: () -> Unit = {},
    audioPlaying: Boolean = false,
    audioBurnOpened: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    val isFile = msg.type == MessageType.FILE
    val isImage = msg.type == MessageType.IMAGE
    val isVideo = msg.type == MessageType.VIDEO
    val isAudio = msg.type == MessageType.AUDIO

    // 焚毁消息（B 阶段）：接收方未点开 → 遮罩；点开后 burnDeadline 非空 → 倒计时显示。
    // 焚毁**语音**特殊：计时改在「听完」才起（burnDeadline 仍为空），故揭示判据要叠加 [audioBurnOpened]
    // （已点开播放但还没听完）——否则点开后仍被遮罩、看不到播放 UI。
    val isBurn = msg.burnAfterRead
    val revealed = burnDeadline != null || (isAudio && audioBurnOpened)
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

        // weight(fill=false)：气泡最多占用「行宽 - 头像」剩余空间（长文件名不再撑满屏幕挤掉头像），
        // 内容短时仍贴合内容、不强制铺满。
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start
        ) {
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
                        onClick = {
                            // 语音（含焚毁遮罩态）一律走 onAudioTap：它负责揭示+播放，并在听完时启动焚毁计时。
                            // 文件（含焚毁遮罩态）一律走 onFileTap：焚毁图/视频/文档在此打开预览/卡片，退出后才起倒计时。
                            if (isAudio) onAudioTap()
                            else if (isFile) onFileTap()
                            else if (masked) onReveal()
                        },
                        onLongClick = { showMenu = true }
                    )
                ) {
                    val contentColor = if (msg.isMine) Color.White else TextPrimary
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        if (masked) {
                            // 遮罩：接收方点开前不显示原文（B 阶段）。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalFireDepartment, null, tint = Warning, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isAudio) "点击收听" else "点击查看", color = contentColor, fontSize = 14.sp)
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
                                // 播放/暂停图标 + 时长；气泡宽度随时长递增（微信式），点击播放（onAudioTap）。
                                Icon(
                                    if (audioPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                    "播放语音",
                                    tint = if (msg.isMine) Accent else Primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.GraphicEq, null, tint = contentColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${msg.audioDurationSec.coerceAtLeast(1)}″",
                                    color = contentColor, fontSize = 14.sp,
                                    modifier = Modifier.widthIn(
                                        min = (20 + msg.audioDurationSec.coerceIn(1, 60) * 2).dp
                                    )
                                )
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
                    // 微信式撤回窗口：发送 2 分钟内可撤回，超时撤回键变灰、点击提示不支持。
                    val recallEnabled = System.currentTimeMillis() - msg.timestamp <= RECALL_WINDOW_MS
                    MessageActionMenu(
                        isMine = msg.isMine,
                        recallEnabled = recallEnabled,
                        onDelete = onDelete,
                        onRecall = onRecall,
                        onRecallBlocked = onRecallBlocked,
                        onDismiss = { showMenu = false }
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 自己发的且未送达：红色调小药丸（红底+红字），示意可点 → 点击重发（文字/文件均可，微信式）。
                if (msg.isMine && msg.status == MessageStatus.FAILED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onResend() }
                            .background(Danger.copy(alpha = 0.1f))
                            .padding(horizontal = 3.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, "未送达", tint = Danger, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("未送达", fontSize = 10.sp, lineHeight = 15.sp, color = Danger)
                    }
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
    recallEnabled: Boolean,
    onDelete: () -> Unit,
    onRecall: () -> Unit,
    onRecallBlocked: () -> Unit,
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
                    // 超 2 分钟：撤回项灰显，点击仍触发 → 提示不支持（微信式）。
                    MessageActionItem("撤回", Icons.Default.Undo, enabled = recallEnabled) {
                        onDismiss()
                        if (recallEnabled) onRecall() else onRecallBlocked()
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionItem(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

/** 撤回时限：消息发送 2 分钟内可撤回（微信式），超时撤回键变灰。 */
private const val RECALL_WINDOW_MS = 2 * 60 * 1000L

/** 时间分隔行阈值：与上一条间隔超过 5 分钟才插入新分隔行（微信式，不刷屏）。 */
private const val TIME_SEPARATOR_GAP_MS = 5 * 60 * 1000L

private fun formatMessageTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

/** 微信式时间分隔行：居中灰字、智能日期格式（[formatTimeSeparator]）。 */
@Composable
private fun TimeSeparator(timestamp: Long) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(formatTimeSeparator(timestamp), fontSize = 11.sp, color = TextSecondary)
    }
}

private val sepTimeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
private val sepWeekFormat = SimpleDateFormat("EEEE HH:mm", Locale.CHINA)
private val sepDateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
private val sepYearFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

/**
 * 智能日期格式（24h）：今天=`14:30`；昨天=`昨天 14:30`；2–6 天内=`星期三 14:30`；
 * 今年更早=`6月10日 14:30`；跨年=`2025年6月10日 14:30`。
 */
private fun formatTimeSeparator(ts: Long): String {
    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayMs = 24L * 60 * 60 * 1000
    val daysAgo = ((startOfDay(System.currentTimeMillis()) - startOfDay(ts)) / dayMs).toInt()
    val sameYear = Calendar.getInstance().get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)
    val d = Date(ts)
    return when {
        daysAgo <= 0 -> sepTimeFormat.format(d)
        daysAgo == 1 -> "昨天 " + sepTimeFormat.format(d)
        daysAgo in 2..6 -> sepWeekFormat.format(d)
        sameYear -> sepDateFormat.format(d)
        else -> sepYearFormat.format(d)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
