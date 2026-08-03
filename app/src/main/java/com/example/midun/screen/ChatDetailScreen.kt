package com.example.midun.screen

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.midun.util.KeyboardHeightStore
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
    // 进行中的焚毁死线：messageId → 截止时刻。已不再显示倒计时，仅用于判定接收方是否已点开（揭示遮罩）。
    val burnTimers by chatViewModel.burnTimers.collectAsState()

    var inputText by remember { mutableStateOf("") }
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
    var burnTextCard by remember { mutableStateOf<ChatMessage?>(null) } // 焚毁文字弹窗的目标消息（关闭弹窗即焚）
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
    // —— 微信式「键盘 ⇄ 功能面板」互斥切换 ——
    // 面板高度取「上次键盘高度」，两者共用输入栏下方同一块空间；该块高度 = max(键盘, 面板, 导航栏)，
    // 于是键盘落、面板起（或反向）时高度不跳变，输入栏一直贴在那块空间之上，正是微信的观感。
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    // 记住键盘高度，面板照此高度开。跨启动持久化（见 [KeyboardHeightStore]）：只活在组合里的话，本次进程内
    // 没弹过键盘就点 ⊕，面板会按写死的猜测值展开，之后再切换就会跳一下。
    var lastImeHeight by remember { mutableStateOf(KeyboardHeightStore.read(context).dp) }
    // 必须等键盘**停稳**再记（`[ui]` 2026-08-01 实测定位）：收起时高度会连续经过 278→200→150→127→0，
    // 若见到 >120 就记，最后留在手里的是「正在收起途中」的 127dp，面板从此按半个键盘的高度开。本效应随
    // imeBottom 每次变化重启，故 delay 能走完就说明这个值已经稳定 250ms，那才是真正的键盘高度。
    LaunchedEffect(imeBottom) {
        if (imeBottom <= 120.dp) return@LaunchedEffect
        delay(250)
        lastImeHeight = imeBottom
        KeyboardHeightStore.write(context, imeBottom.value.toInt())
    }
    // 关面板去弹键盘时按住面板高度，等键盘顶上来再撤（见下方 [panelHeight] 说明）。
    var panelHold by remember { mutableStateOf(false) }
    LaunchedEffect(panelHold, imeBottom) {
        if (!panelHold) return@LaunchedEffect
        // 必须等键盘**升到顶**才撤，不能只判「已经露头」：键盘才到 121dp 就撤，面板同时往下掉，掉得比键盘
        // 升得快，max 照样塌一截——这正是「文件切键盘仍抖一下」的原因。留 8dp 容差给取整与动画末帧。
        if (imeBottom >= lastImeHeight - 8.dp) { panelHold = false; return@LaunchedEffect }
        delay(600) // 兜底：键盘没弹出来（被系统拦、输入法崩了）也得撤，否则空间一直空撑着
        panelHold = false
    }
    /**
     * 面板高度。**任一时刻只允许一条高度在动**（`[ui]` 2026-08-01 修抖动）：
     *
     * 共享空间取 `max(键盘, 面板, 导航栏)`。若键盘落与面板升同时进行，两条曲线在中点交叉——那一刻两者都只有
     * 一半高，`max` 跟着塌一半再弹回来，就是用户看到的「切换时抖一下」。曲线怎么调都躲不开，只能错开：
     * - **只要键盘在场，面板高度就 [snap] 瞬间切换**，视觉全交给键盘自己的升降动画，`max` 全程等于满高；
     * - 关面板去弹键盘 → [panelHold] 把面板按住不撤，等键盘**升到顶**（或 600ms 兜底）才撤。
     *
     * 只有「从彻底收起态开面板」「面板直接收回底部」这两种没有键盘参与的情况才真正播放高度动画。
     */
    val panelHeight by animateDpAsState(
        targetValue = if (showPlusPanel || panelHold) lastImeHeight else 0.dp,
        animationSpec = if (imeBottom > 120.dp) snap() else tween(250),
        label = "plusPanelHeight"
    )
    // 面板淡入淡出（键盘上升时淡出、下落时淡入），避免与键盘交叠的一瞬间硬切。
    val panelAlpha by animateFloatAsState(
        targetValue = if (showPlusPanel && imeBottom < 120.dp) 1f else 0f,
        label = "plusPanelAlpha"
    )
    val keyboardController = LocalSoftwareKeyboardController.current
    // 点空白处/切语音时收起一切，回到「刚进会话」的输入栏贴底态。
    val collapseAll = {
        showPlusPanel = false
        showSourceMenu = false // 面板没了菜单也得走，否则下次开面板它会自己冒出来
        focusManager.clearFocus()
        keyboardController?.hide()
    }
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
    /** 未读新消息计数（用户不在底部时攒着，由右下角气泡告知）。切联系人清零。 */
    var pendingNewCount by remember(contactId) { mutableStateOf(0) }
    /** 列表是否已贴底。`canScrollForward` 为 false 即到底，比自己算 offset 稳。 */
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val lastVisibleIndex by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(atBottom) { if (atBottom) pendingNewCount = 0 } // 自己滑回底部即销账

    // 同时以末条消息 id 为 key：重发会「删旧+插新」使 size 不变，但末条变化仍需滚到底。
    // 键仍取原始 messages：搜索过滤只改可见条数，不该触发「跳到底」。
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (displayMessages.isEmpty()) return@LaunchedEffect
        val last = displayMessages.size - 1
        // 判据取「插入前的末条是否还看得见」（lastVisibleIndex >= last - 1）而非 [atBottom]：新消息此刻已经
        // 插进来了，`atBottom` 必然为 false，用它会把「原本贴着底的用户」也误判成在翻历史。
        val wasAtBottom = lastVisibleIndex >= last - 1
        when {
            !didInitialScroll -> {
                listState.scrollToItem(last)
                didInitialScroll = true
            }
            // 自己发的、或本就贴着底 → 跟到底（客户 2026-08-01 前的唯一行为）
            messages.lastOrNull()?.isMine == true || wasAtBottom -> listState.animateScrollToItem(last)
            // 用户正在翻历史 → 别拽他，攒进计数由气泡提示（微信同款）
            else -> pendingNewCount++
        }
    }

    // 键盘或 ⊕ 面板升起 → 底部始终贴住最新消息（微信同款，客户 2026-08-01）。
    // 布局本就把列表顶起而非盖住，但列表按顶部锚定：视口从下方缩小后，最底下几条会滑出屏幕，用户还得自己往下拖。
    //
    // **瞬时贴底、每帧都贴，而不是播一次滚动动画**（2026-08-01 改）：`animateScrollToItem` 要真的滚过去，
    // 沿途每条消息都得组合测量一遍——从历史深处点回输入框时要一次性趟过几十条（还含图片气泡），又正撞上键盘
    // 升起、列表每帧重新测量，两边抢帧就是肉眼可见的卡顿。改为跟着底部空间高度每变一帧就 `scrollToItem`：
    // 只组合目标那一屏，没有动画开销，观感是内容被键盘顶着走。
    //
    // 只在升起方向贴；落下时不动，免得用户刚翻上去看历史、一收键盘又被拽回来。搜索态不跟：那时列表是过滤
    // 结果，跳到底毫无意义。
    val inputExpanded = imeBottom > 120.dp || showPlusPanel
    LaunchedEffect(imeBottom, panelHeight, showPlusPanel) {
        if (!inputExpanded || searchQuery.isNotBlank() || displayMessages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(displayMessages.size - 1)
    }

    // 手指一拖消息列表就收起键盘与 ⊕ 面板（微信同款，客户 2026-08-01）：要翻历史，先把下面那半屏还回来。
    // judged by DragInteraction 而非 isScrollInProgress——后者连**程序滚动**也算，上面那个「升起即滚到底」
    // 会立刻把自己触发的滚动当成用户翻页，键盘刚弹就被收掉。只认手指按下那一刻，惯性滑行不重复触发。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) collapseAll()
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
                        // 顶栏左侧：联系人名 + 其下连接状态（「已加密」已按客户要求去掉，连接状态搬到它原来的位置，
                        // 不再居中放大）。名 17sp、状态 13sp（客户 2026-08-01 装机看过后定的）——主次拉开一档，
                        // 仍是「先看是谁、再看通没通」。名过长时单行截断，避免换行撑高顶栏。
                        Column(modifier = Modifier.clickable { onOpenProfile() }) {
                            Text(contact?.remark ?: "聊天", fontSize = 17.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (connectedHere) "已连接" else "未连接",
                                    fontSize = 13.sp,
                                    color = if (connectedHere) Accent else Danger
                                )
                                // 点建连链接触发 onGoConnect（内层 clickable 消费事件，不冒泡到进资料页）。
                                if (!connectedHere) {
                                    Text(" · ", fontSize = 13.sp, color = Color.White.copy(0.7f))
                                    Text(
                                        "前往建立连接",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable { onGoConnect() }
                                            .padding(horizontal = 2.dp, vertical = 1.dp)
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
                        // 焚毁时机已定死（文字关弹窗 / 语音听完 / 图·视频·文件关预览，一律即刻焚），故点击
                        // 直接开关，不再弹时长选择框（客户 2026-07-27 定死、2026-07-29 取消文字 15 秒窗口）。
                        IconButton(onClick = {
                            when {
                                !connectedHere -> showBurnGateDialog = true   // 未连接：提示先建联
                                else -> chatViewModel.setBurnMode(!burnMode.enabled)
                            }
                        }) {
                            Icon(
                                Icons.Default.LocalFireDepartment,
                                if (burnOnHere) "关闭阅后即焚" else "阅后即焚",
                                // 未开启显灰（原为白色，看着像已开启，客户要求区分开）。
                                tint = if (burnOnHere) Warning else BurnIconOff
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
              Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 微信式布局：最左语音/键盘切换 → 中间输入框/按住说话 → 最右文件(空)/发送(有内容,渐变)。
                    IconButton(onClick = {
                        showPlusPanel = false                      // 切语音：功能面板一并收起（微信同款）
                        showSourceMenu = false                     // 面板都收了，挂在它上面的来源菜单也别留着
                        if (voiceMode) pendingKeyboardFocus = true // 语音→键盘：切换后自动弹起键盘
                        voiceMode = !voiceMode
                        if (voiceMode) { voicePlayer.stop(); focusManager.clearFocus(); keyboardController?.hide() }
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
                                .height(44.dp) // 与输入框同高（客户 2026-08-01 压低输入区）
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
                        // 输入框（客户 2026-08-01「纵向宽度减小些」）：M3 的 OutlinedTextField 高度写死在
                        // 56dp（内部 defaultMinSize + 16dp 上下内边距），高层 API 不给改内边距，故换成
                        // BasicTextField 自绘边框——外观照旧（1dp 描边、圆角 20dp），高度落到 44dp，与左侧
                        // 语音按钮同高。多行仍可长到 110dp。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 110.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, TextSecondary.copy(0.5f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputText.isEmpty()) {
                                Text("输入消息...", fontSize = 14.sp, color = TextSecondary)
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = TextPrimary),
                                cursorBrush = SolidColor(Primary),
                                maxLines = 4,
                                // 点输入框 → 键盘升起，功能面板同时收起（互斥，微信同款）。
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(inputFocusRequester)
                                    // 点输入框 → 面板让位给键盘，同样先按住高度再撤（见 panelHeight）。
                                    .onFocusChanged {
                                        if (!it.isFocused) return@onFocusChanged
                                        if (showPlusPanel) panelHold = true
                                        showPlusPanel = false
                                    }
                            )
                        }
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
                                // 微信式 ⊕：面板与键盘互斥切换——面板未开则收键盘、开面板；已开则再点回键盘。
                                IconButton(onClick = {
                                    showSourceMenu = false
                                    if (showPlusPanel) {
                                        showPlusPanel = false
                                        panelHold = true // 面板先别塌，等键盘顶上来（见 panelHeight）
                                        if (voiceMode) { voiceMode = false; pendingKeyboardFocus = true }
                                        else runCatching { inputFocusRequester.requestFocus() }
                                    } else {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        showPlusPanel = true
                                    }
                                }) {
                                    Icon(Icons.Default.AddCircleOutline, "更多", tint = Primary)
                                }
                            }
                        }
                    }
                }
                // 输入栏下方的共享空间：键盘、+ 工具栏、系统导航栏三者取最大高度占位。
                // edge-to-edge 下系统不再自动顶起布局，这块占位替代原来的 imePadding/navigationBarsPadding：
                // 键盘落而面板起时高度连续，输入栏不会先掉到底再被顶起（对齐 v4 §6.3 的贴底要求）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxOf(imeBottom, panelHeight, navBottom))
                        .clipToBounds(), // 面板高度动画期间不画到空间之外
                    contentAlignment = Alignment.TopCenter
                ) {
                    // 微信式 + 工具栏（第一格文件、第二格拍摄）：随键盘升降淡出/淡入。
                    if (panelHeight > 0.dp) {
                        PlusToolPanel(
                            modifier = Modifier.graphicsLayer { alpha = panelAlpha },
                            // 点「文件」**不再收面板**（客户 2026-08-03）：原来先把面板塌到底、再在最右边那颗 ⊕
                            // 上弹菜单，用户的视线要从左下角跳到右下角，中间还夹一段面板塌陷动画。现在面板留在原处，
                            // 菜单直接挂在「文件」这一格上（贴底所以自动朝上弹）。
                            onFile = { showSourceMenu = true },
                            onCamera = { showPlusPanel = false; showCamera = true },
                            sourceMenuExpanded = showSourceMenu,
                            onDismissSourceMenu = { showSourceMenu = false },
                            onPickFromPhone = { showSourceMenu = false; pickFileLauncher.launch("*/*") },
                            onPickFromFolder = {
                                showSourceMenu = false
                                showPickDialog = true
                                chatViewModel.loadSaveFolders()
                            }
                        )
                    }
                }
              }
            }
        }
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 点消息区空白（气泡等自身可点元素会先吃掉手势）→ 收键盘 + 收功能面板，回到贴底态。
                .pointerInput(Unit) { detectTapGestures { collapseAll() } }
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
                        // 焚毁文字改弹窗式（客户 2026-07-29）：点击不再就地揭示+倒计时，而是弹窗展示原文，
                        // 关闭弹窗才登记焚毁（ttl=0 → 当场焚）。其余类型维持原就地揭示。
                        onReveal = {
                            if (msg.type == MessageType.TEXT) burnTextCard = msg
                            else chatViewModel.revealBurnMessage(msg.id, contactId, msg.type)
                        },
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
                                // 播放完成回调：普通语音仅复位；焚毁语音**听完即焚**（ttl=0，两端一并删）。
                                val started = voicePlayer.play(tmp) {
                                    playingVoiceId = null
                                    if (burnRecv) chatViewModel.revealBurnMessage(msg.id, contactId, msg.type)
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
                            // 接收方焚毁文件（[chat-voice] 之外的图/视频/文档焚毁）：一次性预览，退出预览即焚。
                            val burnRecv = msg.burnAfterRead && !msg.isMine && msg.type == MessageType.FILE
                            when {
                                // 焚毁文件二次预览拦截：已看过并退出过一次（已登记倒计时）→ 提示不支持二次预览。
                                burnRecv && burnTimers[msg.id] != null -> scope.launch {
                                    snackbarHostState.showSnackbar("阅后即焚文件不支持二次预览")
                                }
                                // 焚毁图/视频首次预览：无「保存到文件夹」按钮（saveTarget=null），退出预览即焚（ttl=0）。
                                burnRecv && isMedia -> openMediaPreview(
                                    chatViewModel.stagingPathFor(msg.id), msg.fileName ?: "", ft, null
                                ) { chatViewModel.revealBurnMessage(msg.id, contactId, msg.type) }
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

        // 「N 条新消息」气泡（微信同款，客户 2026-08-01）：只在用户翻着历史、又有新消息到达时出现，
        // 点一下跳到底并销账。悬在列表之上、贴右下角，不占布局空间。
        AnimatedVisibility(
            visible = pendingNewCount > 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = CardBg,
                shadowElevation = 4.dp,
                modifier = Modifier.clickable {
                    scope.launch { listState.scrollToItem(displayMessages.size - 1) }
                    pendingNewCount = 0
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.KeyboardDoubleArrowDown,
                        null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("${pendingNewCount}条新消息", color = Primary, fontSize = 13.sp)
                }
            }
        }
      }
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
        // 焚毁文档走到这里 = 已在 BurnDocDialog 点开看过 → 无论存成没存、存完都得焚。焚毁会删暂存区副本，
        // 故必须等 saveReceivedFile 回调（拷贝已完成）再焚，不能存和焚并发。
        val burnAfterSave = msg.burnAfterRead && !msg.isMine
        val burnNow = { if (burnAfterSave) chatViewModel.revealBurnMessage(msg.id, contactId, msg.type) }
        SaveToFolderDialog(
            fileName = msg.fileName ?: msg.content,
            folders = chatViewModel.saveFolders.collectAsState().value,
            onPickFolder = { folderId ->
                chatViewModel.saveReceivedFile(msg, folderId) { ok, message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                    burnNow()
                }
                fileToSave = null
            },
            onCreateFolder = { name ->
                chatViewModel.createFolderForSave(
                    name,
                    onCreated = { folderId ->
                        chatViewModel.saveReceivedFile(msg, folderId) { ok, message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                            burnNow()
                        }
                        fileToSave = null
                    },
                    onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                )
            },
            // 放弃保存：这条已经被看过（点开过焚毁卡片），照样焚。
            onDismiss = { burnNow(); fileToSave = null }
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

    // 焚毁文档卡片（文档不支持预览）：显示名+大小；关闭即焚，选「保存到文件夹」则转交保存弹窗、存完再焚。
    burnDocCard?.let { msg ->
        BurnDocDialog(
            fileName = msg.fileName ?: msg.content,
            fileSize = msg.fileSize,
            onSave = {
                burnDocCard = null
                fileToSave = msg
                chatViewModel.loadSaveFolders()
            },
            onDismiss = {
                chatViewModel.revealBurnMessage(msg.id, contactId, msg.type)
                burnDocCard = null
            }
        )
    }

    // 焚毁文字弹窗：展示原文；关闭即焚（ttl=0，两端一并删）。
    burnTextCard?.let { msg ->
        BurnTextDialog(
            content = msg.content,
            onDismiss = {
                chatViewModel.revealBurnMessage(msg.id, contactId, msg.type)
                burnTextCard = null
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
    modifier: Modifier = Modifier,
    onFile: () -> Unit,
    onCamera: () -> Unit,
    sourceMenuExpanded: Boolean,
    onDismissSourceMenu: () -> Unit,
    onPickFromPhone: () -> Unit,
    onPickFromFolder: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Surface)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.width(8.dp))
        // 来源菜单挂在「文件」这一格上：面板贴屏幕底，下方没有空间，Compose 的 DropdownMenu 会自动朝上弹，
        // 正好落在这一格的正上方——点哪儿、菜单从哪儿出来，视线不用跑。
        Box {
            PlusTool(Icons.Default.InsertDriveFile, "文件", onFile)
            DropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = onDismissSourceMenu,
                properties = PopupProperties(focusable = false)
            ) {
                DropdownMenuItem(
                    text = { Text("从手机存储") },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, tint = Primary) },
                    onClick = onPickFromPhone
                )
                DropdownMenuItem(
                    text = { Text("从文件夹") },
                    leadingIcon = { Icon(Icons.Default.Folder, null, tint = Primary) },
                    onClick = onPickFromFolder
                )
            }
        }
        PlusTool(Icons.Default.PhotoCamera, "拍摄", onCamera)
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
 * 焚毁文字弹窗（客户 2026-07-29 改版）：原先是点开就地揭示原文、给 15 秒可读窗口再焚；现改为弹窗展示，
 * **关闭弹窗即焚**、无倒计时。好处是「读完」由用户的关闭动作明确界定，不会因为切后台/滚走而白白烧掉，
 * 也不会在气泡里留一段明文可被截屏窗口（全局 FLAG_SECURE 只防录屏，防不住旁人肉眼）。
 *
 * 长文可滚动；不提供复制入口（选中即可复制会绕过焚毁语义）。
 */
@Composable
private fun BurnTextDialog(content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
        title = { Text("阅后即焚") },
        text = {
            Column {
                Text(
                    content,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(12.dp))
                Text("关闭本窗口后立即焚毁，且不可再次查看。", color = TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了", color = Primary) }
        }
    )
}

/**
 * 焚毁文档卡片：文档不支持预览（见 FilePreviewDialog），焚毁文档点开显示名称+大小。因为看不到内容，
 * 这类**保留「保存到文件夹」**（客户 2026-07-27；可预览的图/视频反之，预览里不给保存）。
 * 关闭本卡片（`onDismiss`）即视为「看过一次」→ 由调用方当场焚毁；走 [onSave] 则等存完再焚。
 */
@Composable
private fun BurnDocDialog(
    fileName: String, fileSize: Long?, onSave: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = Warning) },
        title = { Text(fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                fileSize?.let { Text(formatFileSize(it), color = TextSecondary, fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "阅后即焚文档不支持预览。关闭本窗口后立即焚毁，且不可再次查看；" +
                        "需要留存请先保存到文件夹。",
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onSave) { Text("保存到文件夹", color = Primary) }
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
                // 状态由火焰描边 + 「阅后即焚」标签承载（BurnStatusLabel）。
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
                        Text(
                            "暂无文件夹，请新建一个。",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                }
            }
        },
        // 「新建文件夹」从正文里挪到按钮行、取消右侧（客户 2026-08-03）：它和「创建并保存」本来就是同一条
        // 动作线上的两步，挤在正文里既弱化了它、又让空文件夹时那句提示旁边杵着个孤零零的按钮。
        confirmButton = {
            Button(
                onClick = {
                    if (creating) { if (newName.isNotBlank()) onCreateFolder(newName.trim()) }
                    else creating = true
                },
                enabled = !creating || newName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text(if (creating) "创建并保存" else "新建文件夹") }
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
 * 焚毁消息的状态标签（B 阶段）：只标「这是阅后即焚消息」，不再显示「xx秒后焚毁」倒计时
 * （客户 2026-07-27：时长定死后倒计时是噪音）。发送方那份补一句「对方读后焚毁」。
 */
@Composable
private fun BurnStatusLabel(isMine: Boolean, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocalFireDepartment, null, tint = Warning, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            if (isMine) "阅后即焚 · 对方读后焚毁" else "阅后即焚",
            fontSize = 10.sp,
            color = contentColor.copy(alpha = 0.85f)
        )
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

    // 焚毁消息（B 阶段）：接收方未点开 → 遮罩。所有类型 ttl=0，一旦登记死线就当场焚、气泡随即消失，故
    // 「已揭示」的气泡实际只是一瞬。文字改走弹窗后更是全程不脱遮罩（原文只在 BurnTextDialog 里出现）。
    // 焚毁**语音**特殊：死线在「听完」才落（在此之前 burnDeadline 为空），故揭示判据要叠加 [audioBurnOpened]
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
                            BurnStatusLabel(isMine = msg.isMine, contentColor = contentColor)
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
