package com.example.midun.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import com.example.midun.media.CardFileDataSource
import com.example.midun.viewmodel.PreviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
                    FileType.AUDIO -> AudioPreview(file, vm, bottomInset = onSave != null)
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

    // 用 Canvas 手绘（drawImage 画进当前绘制层，**不创建 graphicsLayer/RenderNode 离屏层**）：安卓 9（API 28）
    // 及以下，FLAG_SECURE 受保护窗口里的硬件离屏层无法正确合成 → 图片全黑（安卓 10 起修复；鸿蒙 2.0 内核为
    // 安卓 10，故正常）。直绘后缩放/平移全程无离屏层，规避黑屏。ContentScale.Fit 的基础缩放手动算，再叠用户 scale/offset。
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
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
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f }
                    onZoomChange(scale > 1f)
                })
            }
    ) {
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        if (iw <= 0f || ih <= 0f) return@Canvas
        val base = minOf(size.width / iw, size.height / ih) // ContentScale.Fit：最长边贴合
        val drawW = iw * base * scale
        val drawH = ih * base * scale
        val left = (size.width - drawW) / 2f + offset.x
        val top = (size.height - drawH) / 2f + offset.y
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
            filterQuality = FilterQuality.High
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

/** 快进/快退步长。10 秒是播放器的通行档位，图标（[Icons.Default.Replay10]/[Icons.Default.Forward10]）也刚好对得上。 */
private const val AUDIO_SEEK_STEP_MS = 10_000L

/**
 * 音频播放（客户需求 2026-08-12：从手机发来的 mp3 点开要能听）。
 *
 * **与视频预览同一条数据通路**：ExoPlayer + [PreviewViewModel.videoFactory] 那个卡内流式 DataSource
 * （边解密边喂、整文件从不落盘），只是音频没有画面，于是不挂 `PlayerView` 而自绘一套控制条。
 * 名字里的 "video" 只是历史叫法，它对任何媒体都通用。
 *
 * **不用 ExoPlayer 自带的控制条**：那套控件是为视频排的（叠在画面上、自动隐藏），一张纯黑底上没有画面
 * 可叠、隐藏了就什么都不剩。自绘还能把「进度条 + 前后 10 秒 + 播放/暂停」按客户要的样子摆开。
 *
 * @param bottomInset 底部是否要给「保存到文件夹」按钮让位（免保存预览场景），避免控制条与它叠在一起。
 */
// ExperimentalMaterial3Api：只为带 thumb/track 的 Slider 重载（自绘滑块与轨道，见下方说明）。
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AudioPreview(file: FileItem, vm: PreviewViewModel, bottomInset: Boolean) {
    val context = LocalContext.current
    var failed by remember(file.id) { mutableStateOf(false) }

    val player = remember(file.id) {
        val source = ProgressiveMediaSource.Factory(vm.videoFactory(file.id))
            .createMediaSource(MediaItem.fromUri(vm.videoUri(file.id)))
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true // 点开即播，省一次点击
        }
    }
    DisposableEffect(file.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    // 播放器状态是命令式的，只能轮询取——250ms 一次，进度条走起来是连续的，开销可忽略。
    // 拖动进度条期间不回写位置，否则手指还没松开就被播放头拽回去。
    var position by remember(file.id) { mutableLongStateOf(0L) }
    var duration by remember(file.id) { mutableLongStateOf(0L) }
    var playing by remember(file.id) { mutableStateOf(false) }
    var dragging by remember(file.id) { mutableStateOf(false) }
    var dragFraction by remember(file.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L // 未就绪时是 TIME_UNSET（负数）
            playing = player.isPlaying
            delay(250)
        }
    }

    if (failed) {
        CenterMessage("音频无法播放，文件可能已损坏")
        return
    }

    val fraction = when {
        dragging -> dragFraction
        duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 72.dp, bottom = if (bottomInset) 120.dp else 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote, null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(88.dp)
            )
        }

        Spacer(Modifier.height(48.dp))

        // 滑杆的**滑块与轨道都自绘**：Material3 新版默认滑块是根竖条，两侧还给轨道留了缺口、末端点了个
        // 停靠圆点，滑块本身又带一层投影——在纯黑底上这些叠起来就是滑块周围一圈发黑的轮廓。这里换成
        // 最朴素的白圆点 + 两段式细轨道，没有投影、没有缺口、没有停靠点。
        Slider(
            value = fraction,
            onValueChange = { dragging = true; dragFraction = it },
            onValueChangeFinished = {
                if (duration > 0) {
                    val target = (dragFraction * duration).toLong()
                    player.seekTo(target)
                    // **必须同时把本地 position 推到目标**：进度条松手后就改看 position 了，而它还是上一次
                    // 轮询（最多 250ms 前）留下的旧值——不写这一行，松手瞬间进度条会先弹回原处，等下一次
                    // 轮询才跳到目标，看起来就是「到位 → 闪回 → 再闪过去」。
                    position = target
                }
                dragging = false
            },
            thumb = {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // 拖动时显示的是手指所在处的时刻，不是播放头——松手才跳过去。
            Text(formatClock(if (dragging) (dragFraction * duration).toLong() else position),
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(formatClock(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }

        Spacer(Modifier.height(28.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            IconButton(
                onClick = { player.seekTo((player.currentPosition - AUDIO_SEEK_STEP_MS).coerceAtLeast(0L)) }
            ) {
                Icon(Icons.Default.Replay10, "后退 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = {
                    // 放完了再点 → 从头再放一遍（ExoPlayer 停在末尾，直接 play 不会有动静）。
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.isPlaying) player.pause() else player.play()
                },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "暂停" else "播放",
                    tint = Color.White, modifier = Modifier.size(44.dp)
                )
            }
            IconButton(
                onClick = {
                    val limit = if (duration > 0) duration else player.currentPosition
                    player.seekTo((player.currentPosition + AUDIO_SEEK_STEP_MS).coerceAtMost(limit))
                }
            ) {
                Icon(Icons.Default.Forward10, "前进 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

/** 毫秒 → `m:ss`（超过一小时给 `h:mm:ss`）。时长未知（0）时显示 `--:--`。 */
private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
