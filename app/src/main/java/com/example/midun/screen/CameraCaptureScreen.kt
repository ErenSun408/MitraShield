package com.example.midun.screen

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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
                        takePhoto(context, imageCapture, flashOn) { file -> onCaptured(file, "image/jpeg") }
                    },
                    onLongPressStart = {
                        recording = startRecording(context, videoCapture) { file ->
                            recording = null
                            onCaptured(file, "video/mp4")
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
    }
}

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
