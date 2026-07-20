package com.example.midun.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import com.example.midun.media.CardFileDataSource
import com.example.midun.viewmodel.PreviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏文件预览（方案 B，安全）。图片：卡内字节内存解码（不落盘）；视频：ExoPlayer + 卡内流式 DataSource
 * （边解密边播、不落整文件）。`FLAG_SECURE` 已全局开启 → 预览界面不可截屏录屏。
 *
 * [onSave] 非空时（接收方免保存预览场景）底部显「保存到文件夹」按钮——隐私文件夹预览不传，则不显示。
 */
@Composable
fun FilePreviewDialog(
    file: FileItem,
    onClose: () -> Unit,
    onSave: (() -> Unit)? = null,
    siblings: List<FileItem>? = null,
    vm: PreviewViewModel = hiltViewModel()
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // 隐私文件夹图片预览支持在同文件夹图片间左右滑动（客户反馈：点开图片不能翻页）。
            // gallery 全为 IMAGE；单文件 / 视频 / 聊天入口（siblings=null）走原单文件预览。
            val gallery = siblings?.takeIf { file.type == FileType.IMAGE && it.size > 1 }
            var titleName by remember { mutableStateOf(file.name) }
            if (gallery != null) {
                val startIndex = gallery.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                val pagerState = rememberPagerState(initialPage = startIndex) { gallery.size }
                // 图片放大（scale>1）时禁用翻页，让拖动用于平移；缩回 1 再允许左右翻页。
                var pagerScrollEnabled by remember { mutableStateOf(true) }
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = pagerScrollEnabled,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    ImagePreview(gallery[page], vm, onZoomChange = { pagerScrollEnabled = !it })
                }
                LaunchedEffect(pagerState.currentPage) {
                    titleName = gallery.getOrNull(pagerState.currentPage)?.name ?: file.name
                }
                Text(
                    "${pagerState.currentPage + 1} / ${gallery.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 14.dp)
                )
            } else {
                when (file.type) {
                    FileType.IMAGE -> ImagePreview(file, vm)
                    FileType.VIDEO -> VideoPreview(file, vm)
                    else -> CenterMessage("该文件类型暂不支持预览")
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White)
            }
            Text(
                titleName,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
            )
            onSave?.let { save ->
                Button(
                    onClick = save,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存到文件夹")
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(file: FileItem, vm: PreviewViewModel, onZoomChange: (Boolean) -> Unit = {}) {
    var image by remember(file.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id) {
        vm.readImageBytes(file.id)
            .onSuccess { bytes ->
                val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, 2048) }
                if (bmp != null) image = bmp.asImageBitmap() else error = "图片解码失败"
            }
            .onFailure { error = it.message ?: "无法读取文件" }
    }
    when {
        image != null -> ZoomableImage(image!!, file.name, onZoomChange)
        error != null -> CenterMessage(error!!)
        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** 可捏合缩放 + 拖动平移 + 双击放大/还原的图片。缩放上限 5x，平移限制在缩放后的边界内。 */
@Composable
private fun ZoomableImage(image: ImageBitmap, contentDesc: String, onZoomChange: (Boolean) -> Unit = {}) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        Image(
            image, contentDesc,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (newScale > 1f) clampOffset(offset + pan, newScale) else Offset.Zero
                        scale = newScale
                        onZoomChange(newScale > 1f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                        onZoomChange(scale > 1f)
                    })
                }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(file: FileItem, vm: PreviewViewModel) {
    val context = LocalContext.current
    // 视频预览期间放开屏幕方向（App 平时锁竖屏）→ 支持横屏看视频；关闭时还原。
    val activity = context.findActivity()
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    val player = remember(file.id) {
        // 暂存缓存（.recv_/.sent_）经 StagingStore 路由（无卡测试本地直读）；隐私文件夹走卡内流式解密。见 PreviewViewModel.videoFactory。
        val factory = vm.videoFactory(file.id)
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(vm.videoUri(file.id)))
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(file.id) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize()
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

/** 解码图片并按 [reqMax] 下采样，避免大图 OOM。 */
private fun decodeSampled(bytes: ByteArray, reqMax: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / sample > reqMax) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
