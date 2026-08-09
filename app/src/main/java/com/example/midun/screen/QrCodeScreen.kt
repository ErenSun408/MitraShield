package com.example.midun.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.midun.network.ConnectionInfo
import com.example.midun.network.P2PSessionManager.ConnectionState
import com.example.midun.ui.theme.*
import com.example.midun.util.rememberNetworkHint
import com.example.midun.viewmodel.ChatViewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import qrcode.QRCode

/**
 * 全进程共用的二维码识别器（`[qr]` 2026-08-09 客户故障：相册里同一张码，第一次识别得了，之后就识别不到，
 * 退出 App 重开又能识别）。
 *
 * **绝不 close，也不要再在别处 getClient**。病根是原先相机预览在 `onDispose` 里 close 掉自己那个 client，
 * 而相机与相册两处取的 options 完全相同（都只开 [Barcode.FORMAT_QR_CODE]）——ML Kit 按 options 复用同一个
 * 底层原生 detector，于是「切一次 Tab」就把整个进程的识别能力关掉了：此后无论新建多少个 client，拿到的
 * 还是那个已关闭的实例，相机与相册一起失灵，只有杀进程才恢复。客户归因到「第二次保存到相册」是巧合，
 * 真正的变量是中间离开过识别页。
 *
 * 常驻一个 detector 的开销可以忽略，随进程回收即可。
 */
/**
 * 二维码四周留白的像素数 = 4 模块 × 每模块 10 px（渲染时的 `withSize(10)`）——QR 规范要求的静区宽度。
 * 两者必须同改：模块尺寸变了这里也得跟着变，否则静区不足 4 模块，静态图解码又会时灵时不灵。
 */
private const val QR_QUIET_ZONE_PX = 40

private val qrScanner: BarcodeScanner by lazy {
    BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
}

/** 二次识别前把图缩到的最大边长。ML Kit 的检测模型内部本就会缩放，喂过大的图只是白费解码时间。 */
private const val DECODE_MAX_EDGE = 1440

/**
 * 从相册图片 [uri] 解码邀请码，识别到调 [onResult]，否则调 [onNone] 并**带上具体原因**。
 *
 * **两次尝试**（`[qr]` 2026-08-09 客户故障：相册选图识别不出，相机扫同一张码却正常）：
 * 1. 原图直接喂 ML Kit；
 * 2. 没找到码就[缩放 + 四周补白][paddedForDecode]再试一次。这一步同时兜住两个已知成因——分享出去的
 *    PNG 四周没有静区（quiet zone），以及整张图几乎被二维码占满时 ML Kit 那个 SSD 检测模型定位不到码。
 *    相机那条路两样都不占：取景框里连带拍进了周围界面，码本身也只占画面一部分——这正是「相机行、
 *    相册不行」的由来。
 *
 * **失败原因分三种报**，不再一律「未识别到」：读不出图、ML Kit 调用失败、图里确实没有码，排障时是
 * 完全不同的方向。原先合并成一句，现场回报等于没有信息。
 */
private fun decodeQrFromImage(
    context: Context,
    uri: Uri,
    onResult: (String) -> Unit,
    onNone: (reason: String) -> Unit
) {
    val source = runCatching {
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
    }.getOrNull() ?: run {
        onNone("无法读取这张图片，请换一张再试")
        return
    }
    val size = "${source.width}×${source.height}"

    // 第二次尝试：缩放 + 补白后重来。两次都没找到才算真没有。
    fun retryPadded() {
        val padded = runCatching { paddedForDecode(source) }.getOrNull()
            ?: return onNone("图中未找到二维码（图片 $size）")
        qrScanner.process(InputImage.fromBitmap(padded, 0))
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (raw != null) onResult(raw) else onNone("图中未找到二维码（图片 $size，已补白重试）")
            }
            .addOnFailureListener { e ->
                onNone("识别失败：${e.javaClass.simpleName}（图片 $size）")
            }
    }

    qrScanner.process(InputImage.fromBitmap(source, 0))
        .addOnSuccessListener { barcodes ->
            val raw = barcodes.firstOrNull()?.rawValue
            if (raw != null) onResult(raw) else retryPadded()
        }
        .addOnFailureListener { retryPadded() }
}

/** 缩到 [DECODE_MAX_EDGE] 以内并四周补一圈白边（短边的 8%，至少 16px），供二次识别用。 */
private fun paddedForDecode(src: Bitmap): Bitmap {
    val scale = minOf(1f, DECODE_MAX_EDGE.toFloat() / maxOf(src.width, src.height))
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val pad = (minOf(w, h) * 0.08f).toInt().coerceAtLeast(16)
    val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(src, null, Rect(pad, pad, pad + w, pad + h), null)
    }
    return out
}

/** 把邀请码 [bitmap] 写入 cache 经 FileProvider 内容 URI，调系统分享（image/png）。 */
private fun shareQrImage(context: Context, bitmap: Bitmap) {
    runCatching {
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, "midun_qr.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享邀请码"))
    }
}

/** 复制邀请链接到剪贴板。Android 13+ 系统自带「已复制」浮层，再弹 Toast 就重复了。 */
private fun copyInviteLink(context: Context, link: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("波波邀请链接", link))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "邀请链接已复制", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 读剪贴板里的纯文本，空则返回 null。供识别页的粘贴框用（见 [ScanTab]）。
 *
 * `coerceToText` 而非 `text`：从微信复制来的可能是带样式的富文本项，取 `text` 会拿到 null。
 * Android 12+ 读剪贴板时系统会自己弹一句「已粘贴」，属预期行为，不必再补 Toast。
 */
private fun readClipboardText(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}.getOrNull()?.takeIf { it.isNotBlank() }

/** 把邀请链接交给系统分享（text/plain），与 [shareQrImage] 同路数、只是载荷是文本。 */
private fun shareInviteLink(context: Context, link: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        context.startActivity(Intent.createChooser(intent, "转发邀请链接"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScreen(
    onBack: () -> Unit,
    onOpenChat: (contactId: String) -> Unit = {},
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val mainContext = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0=生成 1=识别
    var isGenerating by remember { mutableStateOf(false) }
    var qrGenerated by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(120) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    // 邀请链接：与 qrBitmap 同生同灭（同一次 prepareConnection 的产物），见 [ConnectionInfo.toLink]。
    var inviteLink by remember { mutableStateOf<String?>(null) }

    // 扫码状态（patch §M6 改动4）
    var scanned by remember { mutableStateOf(false) }
    var scannedContent by remember { mutableStateOf("") }
    // 识别页的链接输入框内容（在这儿 remember 才能跨 Tab 切换保留，见 [ScanTab]）。
    var linkInput by remember { mutableStateOf("") }
    // 新流程：扫到码即连接（占位备注），连上后才弹备注框。
    var connecting by remember { mutableStateOf(false) }            // 连接进行中（扫码后立即连接）
    var connectError by remember { mutableStateOf<String?>(null) }  // 连接失败提示（可重扫）
    // 非空 = 连接已建立且为「新建联系人」→ 弹备注框；已是好友（重连）不弹、直接进会话。
    var remarkContactId by remember { mutableStateOf<String?>(null) }

    // 真实连接状态（M10.3）：源自 P2PSessionManager 单例，A 出码后显示等待/已连接。
    val connectionState by chatViewModel.connectionState.collectAsStateWithLifecycle()

    // 底部网络提示条：文案随网络实时变化；叉掉后本次停留在本页期间不再出现（重进本页会重新显示）。
    val networkHint = rememberNetworkHint()
    var showNetworkHint by remember { mutableStateOf(true) }

    // 后台活动受限提示条 + 点「去设置」弹的引导框（与首次插卡那次同一个框，见 [BackgroundActivityGuide]）。
    var showBackgroundHint by remember { mutableStateOf(true) }
    var showBackgroundGuide by remember { mutableStateOf(false) }
    if (showBackgroundGuide) {
        // 用户自己点「去设置」打开的，不给「不再询问」——那是他主动要看的东西。
        BackgroundActivityGuideDialog(
            onDismiss = { showBackgroundGuide = false },
            onOpenSettings = {
                showBackgroundGuide = false
                BackgroundActivityGuide.openAppSettings(mainContext)
            }
        )
    }

    // 离开本屏时：若未建立连接，停止监听释放 ServerSocket；已连接则保留会话供后续聊天（M10.4）。
    DisposableEffect(Unit) {
        onDispose {
            if (chatViewModel.connectionState.value != ConnectionState.CONNECTED) {
                chatViewModel.stopConnection()
            }
        }
    }

    // A（出码方）：对端扫码连上并完成身份交换 → 新建联系人则弹备注、已是好友直接进会话。
    LaunchedEffect(Unit) {
        chatViewModel.peerIdentified.collect { (contactId, isNew) ->
            if (isNew) remarkContactId = contactId else onOpenChat(contactId)
        }
    }

    // A（出码方）：对端已连入但握手失败 → 弹与扫码端同款的失败弹窗（复用 connectError）。
    // 注意只有「已经连到本机」的失败才会到这儿；对端根本没连通的失败 A 侧看不到，只能由扫码端自己报。
    LaunchedEffect(Unit) {
        chatViewModel.listenerError.collect { msg -> connectError = msg }
    }

    LaunchedEffect(qrGenerated) {
        if (qrGenerated) {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            // 过期：清状态与图像，回到 pre-gen，下次需重新点按钮渲染。未连上则一并停监听。
            // 链接同码一起作废——它俩本就是同一份连接信息的两种载体。
            qrGenerated = false
            countdown = 120
            qrBitmap = null
            inviteLink = null
            if (chatViewModel.connectionState.value != ConnectionState.CONNECTED) {
                chatViewModel.stopConnection()
            }
        }
    }

    // 渲染 LaunchedEffect 提到顶层（脱离 pre-gen 分支），让 post-gen 的"重新生成"
    // 也能复用同一渲染路径——原地刷新 bitmap/content + 重置倒计时，不退回 pre-gen 卡片。
    LaunchedEffect(isGenerating) {
        if (isGenerating) {
            // M10.3：生成真实 ConnectionInfo（含本机 IPv6 + 临时 ECDH 公钥）并后台开始监听对端连入。
            val content = chatViewModel.prepareConnection()
            val bitmap = runCatching {
                withContext(Dispatchers.Default) {
                    val pngBytes = QRCode.ofSquares()
                        .withSize(10)
                        .build(content)
                        .render()
                        .getBytes()
                    val decoded = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                    // 压到不透明白底上：render() 产出的 PNG 带透明背景，分享到部分机型会被填成黑色。
                    // flatten 后屏显与分享的 PNG 均为白底二维码。
                    //
                    // **四周必须留静区**（`[qr]` 2026-08-09 客户故障：相册选图报「未在图片中识别到邀请码」，
                    // 而相机直接扫同一张码却正常）。QR 规范要求码四周有 4 个模块宽的空白（quiet zone），
                    // 而 qrcode-kotlin 的 `margin` 默认是 0、我们也没设过 → 渲出来的位图是**边到边**的。
                    // 相机那条路侥幸能用：取景框里连带拍进了卡片白底与页面背景，静区是屏幕 UI 白送的；
                    // 分享出去的 PNG 没这个便宜可占，ML Kit 静态解码就此失败——与微信压缩无关，那张图
                    // 本来就缺白边。
                    //
                    // 不走库的 `withMargin()`：它在 build() 里执行 `resize(canvasSize + margin * 2)`，而
                    // canvasSize 默认 0（= 让库按数据自动算），于是会把一张几百像素的码 resize 成 80px 见方，
                    // 直接毁掉。自己在这一步加，尺寸完全可控。
                    val quietZone = QR_QUIET_ZONE_PX
                    val flattened = Bitmap.createBitmap(
                        decoded.width + quietZone * 2,
                        decoded.height + quietZone * 2,
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(flattened).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(decoded, quietZone.toFloat(), quietZone.toFloat(), null)
                    }
                    decoded.recycle()
                    flattened
                }
            }.getOrNull()
            if (bitmap != null) {
                qrBitmap = bitmap
                inviteLink = ConnectionInfo.linkOf(content) // 同一份 content，只换个载体
                countdown = 120
                qrGenerated = true
            } else {
                qrError = "邀请码生成失败，请重试"
            }
            isGenerating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同波相契") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        // 底部两条提示条，都是「建联失败最常见的原因」，都可叉掉（本次进入本页内不再显示）。
        // 上面那条：后台活动受限（`[network]` 2026-08-03，真机定位的断连根因）——**常驻不检测**，因为厂商那个
        //   开关既没有 API 能查、界面也被签名级权限锁死（见 [BackgroundActivityGuide]）。
        // 下面那条：网络类型（客户需求 2026-07-31）——当前跨网直连只在电信数据↔电信数据之间实测通过。
        // 两条都**纯提示，不参与任何连接判定**。
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (showBackgroundHint) {
                    HintBar(
                        text = BackgroundActivityGuide.HINT,
                        onDismiss = { showBackgroundHint = false },
                        actionText = "去设置",
                        onAction = { showBackgroundGuide = true }
                    )
                }
                if (showNetworkHint) {
                    HintBar(text = networkHint, onDismiss = { showNetworkHint = false })
                }
            }
        }
    ) { padding ->
        // 出码前只剩一颗按钮（连接信息卡已删）→ 该状态不滚动、用 weight 留白把按钮压到页面中间（客户要求）；
        // 其余状态（已出码的长内容、识别页）仍需滚动，故滚动修饰按状态挂。
        val centerButton = selectedTab == 0 && !qrGenerated
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 内容超屏可滚动，确保底部分享按钮可触及
                .then(if (centerButton) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tab切换
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Surface,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("生成邀请码")
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("识别邀请码")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (selectedTab == 0) {
                // 生成邀请码
                if (!qrGenerated) {
                    // 上下等分留白 → 按钮落在页面高度正中（客户要求；原来上方那张「连接信息」卡片已删）。
                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            qrError = null
                            isGenerating = true
                        },
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.QrCode, null)
                            Spacer(Modifier.width(8.dp))
                            Text("生成邀请码与链接")
                        }
                    }

                    // 有效期注释：原来是「连接信息」卡里的一行，卡删掉后单独挂在按钮下方。
                    Spacer(Modifier.height(10.dp))
                    Text("有效期 120 秒", fontSize = 12.sp, color = TextSecondary)

                    qrError?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }

                    Spacer(Modifier.weight(1f))
                } else {
                    // 显示二维码
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(2.dp, Primary, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "邀请码",
                                modifier = Modifier.size(200.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 倒计时
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, null, tint = if (countdown < 30) Danger else Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "有效期：${countdown}秒",
                            color = if (countdown < 30) Danger else Accent,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 真实连接状态（M10.3）：等待对端扫码连入 → 已建立加密连接
                    when (connectionState) {
                        ConnectionState.CONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("已成功建立连接", color = Success, fontWeight = FontWeight.Medium)
                        }
                        ConnectionState.LISTENING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Primary)
                            Spacer(Modifier.width(8.dp))
                            Text("等待对方扫码连接…", color = TextSecondary, fontSize = 13.sp)
                        }
                        // 对端已落到 accept、正在握手（客户需求 2026-07-31：出码方也要看得见进展）。
                        ConnectionState.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("对方连接中…", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        else -> {}
                    }

                    Spacer(Modifier.height(20.dp))

                    // 邀请码本身的两个动作同行：左「重新生成」、右「分享邀请码」，宽度 1:2。
                    // 摆在链接区上面——它俩管的是上面那张码，链接区是另一件事，别混在一起。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 原地刷新：触发顶层渲染 LaunchedEffect 重渲一张并重置倒计时，不退回 pre-gen 卡片。
                        OutlinedButton(
                            onClick = {
                                qrError = null
                                isGenerating = true
                            },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重新生成", fontSize = 13.sp, maxLines = 1)
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        OutlinedButton(
                            onClick = { qrBitmap?.let { shareQrImage(mainContext, it) } },
                            enabled = qrBitmap != null,
                            modifier = Modifier.weight(2f)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享邀请码", fontSize = 13.sp, maxLines = 1)
                        }
                    }

                    qrError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }

                    // 邀请链接区（客户需求 2026-08-07）：**二维码的副产品**，供对方相机故障 / 不便扫码时改用。
                    // 内容、有效期、监听端口都与上面那张码同源，不是第二条独立通道。
                    inviteLink?.let { link ->
                        Spacer(Modifier.height(24.dp))

                        Text(
                            "通过邀请链接建立会话",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 链接很长（约 400 字符），只给一行 + 省略号；要用它的人走复制/转发，不靠肉眼读。
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(FieldBg)
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    link,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            LinkActionButton(Icons.Default.ContentCopy, "复制邀请链接") {
                                copyInviteLink(mainContext, link)
                            }
                            Spacer(Modifier.width(8.dp))
                            LinkActionButton(Icons.Default.Share, "转发邀请链接") {
                                shareInviteLink(mainContext, link)
                            }
                        }
                    }
                }
            } else {
                ScanTab(
                    scanned = scanned,
                    linkInput = linkInput,
                    onLinkInputChange = { linkInput = it },
                    onQrDetected = { value ->
                        // 新流程：先连接（空备注→默认「新建联系人N」），成功后再弹备注；已是好友则直接进会话。
                        scanned = true
                        scannedContent = value
                        connecting = true
                        connectError = null
                        chatViewModel.connectToContact(
                            qrContent = value,
                            remark = "", // 空 → bindContact 用「新建联系人N」默认名
                            onConnected = { contactId, isNew ->
                                connecting = false
                                if (isNew) remarkContactId = contactId else onOpenChat(contactId)
                            },
                            onError = { msg ->
                                connecting = false
                                connectError = msg
                                // 这里**不**解禁扫描：错误弹窗弹出时相机仍在取景、码还在画面里，一解禁就会
                                // 立刻重扫→重连→再失败→弹窗刷新，循环刷屏（2026-07-31 现场日志：跨网时
                                // 每次立即 ENETUNREACH，200ms 一轮连刷 17 次）。改为关弹窗时才允许重扫。
                            }
                        )
                    }
                )
            }
        }
    }

    // 扫码后连接进行中：不可关闭的进度提示。
    if (connecting) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Default.Link, null, tint = Primary) },
            title = { Text("正在连接") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Primary)
                    Spacer(Modifier.width(12.dp))
                    Text("正在建立端到端连接…", color = TextSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {}
        )
    }

    // 连接失败：提示原因。**关掉弹窗才解禁扫描**——见 onError 处说明，否则弹窗期间会被同一个码反复触发。
    connectError?.let { msg ->
        val dismiss = {
            connectError = null
            scanned = false // 此刻才允许重扫
        }
        AlertDialog(
            onDismissRequest = dismiss,
            icon = { Icon(Icons.Default.ErrorOutline, null, tint = Danger) },
            title = { Text("连接失败") },
            text = { Text(msg, color = TextSecondary, fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = dismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("知道了") }
            }
        )
    }

    // 连接已建立 + 新建联系人 → 弹备注框（两端共用：B 扫码连上 / A 被扫连上）。填或跳过都进会话。
    remarkContactId?.let { contactId ->
        var remark by remember(contactId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { }, // 已连上，只能用「完成/跳过」离开，避免误触双重导航
            icon = { Icon(Icons.Default.PersonAdd, null, tint = Primary) },
            title = { Text("为联系人添加备注") },
            text = {
                Column {
                    Text("已建立端到端连接，给对方设置一个备注名（可跳过）。", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text("备注名称（如：张三）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (remark.isNotBlank()) chatViewModel.updateRemark(contactId, remark.trim())
                        remarkContactId = null
                        onOpenChat(contactId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = { remarkContactId = null; onOpenChat(contactId) }) {
                    Text("跳过", color = TextSecondary)
                }
            }
        )
    }
}

/** 邀请链接右侧的圆角方形图标按钮（复制 / 转发）。只用 icon，尺寸与左侧链接框等高。 */
@Composable
private fun LinkActionButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FieldBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = Primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ScanTab(
    scanned: Boolean,
    // 链接输入提到 QrCodeScreen：切到「生成」Tab 再切回来时 ScanTab 会离场，
    // 状态留在这儿会被清掉，用户粘了一半的链接就没了。
    linkInput: String,
    onLinkInputChange: (String) -> Unit,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // 相册选图扫码：选一张图 → MLKit 解码二维码 → 走与相机扫码同一条 onQrDetected 路径。
    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            decodeQrFromImage(context, it, onQrDetected) { reason ->
                // 带原因、给长时——现场回报「识别不到」时，这句话就是唯一的线索。
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 首次进入识别 Tab、未授权 → 自动弹一次系统权限框。被拒后改为手动点按钮重试。
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PrimaryDark)
            .border(2.dp, Primary, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (hasCameraPermission) {
            CameraPreview(scanned = scanned, onQrDetected = onQrDetected)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CameraAlt,
                    null,
                    tint = Color.White.copy(0.5f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("需要相机权限以扫描邀请码", color = Color.White.copy(0.8f), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("授权相机")
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = { albumLauncher.launch("image/*") },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Image, null)
        Spacer(Modifier.width(8.dp))
        Text("从手机相册选择邀请码图片")
    }

    Spacer(Modifier.height(8.dp))

    // 说明卡收在扫码这一半的末尾（客户 2026-08-07）：它讲的是「识别之后会发生什么」，
    // 归属上属于上面的扫码/相册，不该夹在下面的链接区里。
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                // 诚实文案（M10.9）：不承诺「签名校验」——设备身份签名校验需安全卡身份密钥作信任根，
                // 尚未启用；客户 2026-07-27 要求连这句括号说明也去掉，只留最朴素的一句。
                "识别后将建立端到端连接",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // 链接入口（客户需求 2026-08-07）：粘贴对方转发来的邀请链接，走与扫码**同一条** onQrDetected 路径
    // ——ConnectionInfo.parse 同时吃 JSON 与链接，故连接/失败/备注弹窗等后续行为完全一致。
    Text(
        "通过邀请链接加入会话",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start,
        fontSize = 13.sp,
        color = TextPrimary,
        fontWeight = FontWeight.Medium
    )

    Spacer(Modifier.height(8.dp))

    // 点一下即粘贴，**不做成输入框**（客户 2026-08-09）。两个理由：
    // ① 这里的链接只可能是从微信复制来的，没有手打的场景，而点输入框必然唤起软键盘、挡掉半屏；
    // ② 原先那个 OutlinedTextField 被压到 48dp（M3 默认 56dp），在部分机型/字号下会把提示文字
    //    切掉下半截——M3 的内边距是按 56dp 排的，强行压矮就是截字，不是"压矮不会截字"。
    // 整条框就是按钮，右侧不再另配粘贴图标（客户 2026-08-09）：出码页那两个图标是因为「复制」和
    // 「转发」是两件事，这里只有粘贴一个动作，图标纯属重复。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FieldBg)
            .clickable {
                val text = readClipboardText(context)
                if (text != null) {
                    onLinkInputChange(text)
                } else {
                    Toast.makeText(context, "剪贴板是空的，请先复制对方发来的邀请链接", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 链接约 400 字符，只给一行 + 省略号：粘进来是为了点「确认连接」，不是给人读的。
        Text(
            linkInput.ifBlank { "点击粘贴邀请链接" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = if (linkInput.isBlank()) TextSecondary else TextPrimary
        )
    }

    Spacer(Modifier.height(10.dp))

    Button(
        // 与相机同一把 scanned 锁：一次连接在飞时，别让人再从这儿点第二次。
        onClick = { onQrDetected(linkInput.trim()) },
        enabled = linkInput.isNotBlank() && !scanned,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary)
    ) {
        Icon(Icons.Default.Link, null)
        Spacer(Modifier.width(8.dp))
        Text("确认连接")
    }
}

@Composable
private fun CameraPreview(
    scanned: Boolean,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 读取最新的 scanned 闭包值：analyzer 注册一次但每帧都读这个 state。
    val scannedState = rememberUpdatedState(scanned)
    val onQrDetectedState = rememberUpdatedState(onQrDetected)

    // 离开识别 Tab / 屏幕销毁时显式 unbind 相机。
    // CameraX 虽绑定到 Activity lifecycle，但 Tab 切回生成后 AndroidView 离场，
    // 相机会继续占用传感器、analyzer 持续解码——主动释放避免泄漏。
    //
    // **不关 [qrScanner]**：它是全进程共用的，在这里关掉会把相册识别一并废掉（见其说明）。
    // 要释放的是相机，不是识别器；识别器随进程回收。
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            if (scannedState.value) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                qrScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstOrNull()?.rawValue?.let { raw ->
                                            if (!scannedState.value) {
                                                onQrDetectedState.value(raw)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}


