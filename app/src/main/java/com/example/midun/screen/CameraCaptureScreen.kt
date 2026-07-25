package com.example.midun.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.midun.ui.theme.Primary
import com.example.midun.util.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 聊天内拍摄（微信式）：全屏相机页，轻触拍照、长按录像。**捕获直落 App 私有目录**（`cacheDir`，别人访问不了）、
 * 绝不经系统相机/相册——安全 App 不能在 DCIM 留明文。拍完把临时文件交回调用方（[onCaptured]）流式加密进卡后即删。
 *
 * 布局：左下闪光灯 / 中间快门 / 右下翻转；快门上方「轻触拍照 · 长按摄像」提示；右上退出。
 *
 * @param onCaptured 拍照(mime=image/jpeg)或录像(mime=video/mp4)完成，传回临时文件；调用方发送后须删。
 * @param onExit 用户点右上退出（未捕获）。
 */
@Composable
fun CameraCaptureScreen(
    onCaptured: (file: File, mime: String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun camGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCameraPerm by remember { mutableStateOf(camGranted()) }
    // 相机必需（拒绝即退），麦克风可选（拒绝则录无声视频）。一次请求两个。
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCameraPerm = result[Manifest.permission.CAMERA] ?: camGranted()
        if (!hasCameraPerm) onExit()
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // 用例句柄：预览 + 拍照 + 录像。翻转/闪光切换时重绑。
    var lensBack by remember { mutableStateOf(true) }
    var flashOn by remember { mutableStateOf(false) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember { Recorder.Builder().build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    // 拍完待确认的捕获（微信式预览）：非空即在取景器之上盖一层预览，取消回取景器接着拍、发送才交给会话。
    var pending by remember { mutableStateOf<Pair<File, String>?>(null) }
    // 丢弃待确认捕获：**必须删临时文件**——安全 App 不能在手机上留下未发送的明文照片/视频。
    fun discardPending() {
        pending?.let { (file, _) -> runCatching { file.delete() } }
        pending = null
    }
    BackHandler(enabled = pending != null) { discardPending() }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordSeconds by remember { mutableStateOf(0) }
    val isRecording = recording != null

    // 录像计时（UI 秒数）。
    LaunchedEffect(isRecording) {
        recordSeconds = 0
        while (isRecording) { delay(1000); recordSeconds++ }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recording?.stop() }
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            // 离开相机页时还挂着未发送的捕获（如被导航走）→ 删掉临时明文，不留残留。
            pending?.let { (file, _) -> runCatching { file.delete() } }
        }
    }

    if (!hasCameraPerm) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {}
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // —— 相机预览 —— 绑定随 lensBack/flashOn 变化重建。
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                bindCamera(ctx, previewView, lifecycleOwner, lensBack, flashOn, imageCapture, videoCapture)
                previewView
            },
            update = { previewView ->
                bindCamera(context, previewView, lifecycleOwner, lensBack, flashOn, imageCapture, videoCapture)
            },
            modifier = Modifier.fillMaxSize()
        )

        // —— 右上退出 —— 录像中禁用（先松手结束录像）。
        Icon(
            Icons.Default.Close, "退出",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(28.dp)
                .then(if (isRecording) Modifier else Modifier.clickableNoRipple { onExit() })
        )

        // —— 录像中：顶部红点 + 计时 ——
        if (isRecording) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.Red))
                Spacer(Modifier.width(6.dp))
                Text("${recordSeconds}″", color = Color.White, fontSize = 14.sp)
            }
        }

        // —— 底部操作区 ——
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 快门上方提示（录像中改显「松开结束」）。
            Text(
                if (isRecording) "松开结束录像" else "轻触拍照 · 长按摄像",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // 左下：闪光灯（录像中禁用切换）。
                Icon(
                    if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    "闪光灯",
                    tint = if (flashOn) Color(0xFFFFC107) else Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 36.dp)
                        .size(28.dp)
                        .then(if (isRecording) Modifier else Modifier.clickableNoRipple { flashOn = !flashOn })
                )

                // 中间：快门。轻触拍照、长按录像。
                ShutterButton(
                    isRecording = isRecording,
                    onTap = {
                        takePhoto(context, imageCapture, flashOn) { file -> pending = file to "image/jpeg" }
                    },
                    onLongPressStart = {
                        recording = startRecording(context, videoCapture) { file ->
                            recording = null
                            pending = file to "video/mp4"
                        }
                    },
                    onLongPressEnd = {
                        runCatching { recording?.stop() } // 停止后由 VideoRecordEvent.Finalize 回调 onCaptured
                    }
                )

                // 右下：翻转前后摄（录像中禁用）。
                Icon(
                    Icons.Default.Cameraswitch, "翻转",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 36.dp)
                        .size(28.dp)
                        .then(if (isRecording) Modifier else Modifier.clickableNoRipple { lensBack = !lensBack })
                )
            }
        }

        // —— 拍完的确认层（微信式）—— 盖在取景器之上（相机保持绑定，取消即刻回到取景，无重启闪烁）。
        pending?.let { (file, mime) ->
            CapturePreview(
                file = file,
                mime = mime,
                onCancel = { discardPending() },
                onSend = {
                    val sent = file to mime
                    pending = null            // 先清，免得 onDispose 把已交出去的文件删了
                    onCaptured(sent.first, sent.second)
                }
            )
        }
    }
}

/**
 * 拍摄确认层（微信式）：全屏看拍到的照片/视频，底部「取消」回取景器、「发送」交给会话。
 *
 * - 照片按 EXIF 朝向摆正（CameraX 写出的 JPEG 靠 EXIF 记方向，直接解码会躺倒），并下采样防大图 OOM。
 * - 用普通 [Image] 而非 `Modifier.graphicsLayer`：全局 FLAG_SECURE 下，API 28 及以下的离屏层合成不出来
 *   会整屏全黑（见 `7f8cacd`）。此层不缩放不平移，不需要任何离屏层。
 * - 视频用 ExoPlayer 循环播放（无控制条，像微信一样自动播）。
 * - 根部吃掉点击，避免手势穿到下面的快门/翻转。
 */
@Composable
private fun CapturePreview(
    file: File,
    mime: String,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { /* 吞掉，别穿到取景器控件 */ } }
    ) {
        if (mime.startsWith("video/")) {
            val player = remember(file.path) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    repeatMode = Player.REPEAT_MODE_ALL
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            var image by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(file.path) {
                image = withContext(Dispatchers.IO) { loadCapturedImage(file)?.asImageBitmap() }
            }
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = "拍摄预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 底部：左取消（回取景器接着拍）／右发送。
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 44.dp)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewAction(Icons.Default.Close, "取消", Color.White.copy(alpha = 0.25f), onCancel)
            PreviewAction(Icons.Default.Check, "发送", Primary, onSend)
        }
    }
}

/** 确认层的一个圆形操作按钮（图标 + 下方文字）。 */
@Composable
private fun PreviewAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    circleColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(circleColor)
                .clickableNoRipple(onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/**
 * 读拍到的 JPEG → 下采样解码 → 按 EXIF 朝向旋转。失败回 null（预览显黑底，发送仍可用）。
 * 只影响本机预览：发出去的是原文件，EXIF 随文件走。
 */
private fun loadCapturedImage(file: File): Bitmap? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val bmp = decodeSampledBitmap(bytes, PREVIEW_MAX_PX) ?: return null
    val degrees = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
}

/** 确认层图片解码的最长边上限（够全屏清晰，又不至于大图 OOM）。 */
private const val PREVIEW_MAX_PX = 2048

/** 快门：白色大圆环，录像中变红缩小。轻触=[onTap]；长按=录像([onLongPressStart]→松手[onLongPressEnd])。 */
@Composable
private fun ShutterButton(
    isRecording: Boolean,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit
) {
    val ring = if (isRecording) Color.Red else Color.White
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPressStart() },
                    onPress = {
                        // onPress 挂起等松手，配合 onLongPress 实现「长按录、松手停」。
                        tryAwaitRelease()
                        onLongPressEnd()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(if (isRecording) 34.dp else 60.dp)
                .clip(CircleShape)
                .background(ring)
        )
    }
}

/** 无涟漪点击（相机页图标用，避免默认 Material 涟漪在黑底上突兀）。 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }

/** 绑定预览 + 拍照 + 录像用例到生命周期。翻转/闪光变化时重绑（先 unbindAll）。绑定失败静默（黑屏预览）。 */
private fun bindCamera(
    ctx: android.content.Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    lensBack: Boolean,
    flashOn: Boolean,
    imageCapture: ImageCapture,
    videoCapture: VideoCapture<Recorder>
) {
    val future = ProcessCameraProvider.getInstance(ctx)
    future.addListener({
        val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val selector = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        runCatching {
            provider.unbindAll()
            // 拍照与录像同时绑定：CameraX 会据设备能力协商（多数设备支持 Preview+ImageCapture+VideoCapture 并存）。
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, videoCapture)
        }
    }, ContextCompat.getMainExecutor(ctx))
}

/** 拍照 → App 私有 cacheDir 的临时 jpg（不经系统相册）。成功回调临时文件。 */
private fun takePhoto(
    ctx: android.content.Context,
    imageCapture: ImageCapture,
    flashOn: Boolean,
    onSaved: (File) -> Unit
) {
    imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    val file = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(ctx),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) { onSaved(file) }
            override fun onError(e: ImageCaptureException) { runCatching { file.delete() } }
        }
    )
}

/**
 * 开始录像 → App 私有 cacheDir 的临时 mp4（不经系统相册）。麦克风已授权则录音，否则录无声。
 * 返回 [Recording] 句柄供松手 `stop()`；[VideoRecordEvent.Finalize] 时回调临时文件（含出错，出错则删并不回调）。
 */
private fun startRecording(
    ctx: android.content.Context,
    videoCapture: VideoCapture<Recorder>,
    onFinalized: (File) -> Unit
): Recording {
    val file = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.mp4")
    val options = FileOutputOptions.Builder(file).build()
    val micGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val pending = videoCapture.output.prepareRecording(ctx, options).let {
        if (micGranted) it.withAudioEnabled() else it
    }
    // 事件回调走主线程 executor：内部要改 Compose 状态 + 触发发送/导航。
    return pending.start(ContextCompat.getMainExecutor(ctx)) { event ->
        if (event is VideoRecordEvent.Finalize) {
            if (event.hasError()) {
                runCatching { file.delete() }
            } else {
                onFinalized(file)
            }
        }
    }
}
