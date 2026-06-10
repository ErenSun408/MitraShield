package com.example.midun.screen

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 */
@Composable
fun FilePreviewDialog(
    file: FileItem,
    onClose: () -> Unit,
    vm: PreviewViewModel = hiltViewModel()
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (file.type) {
                FileType.IMAGE -> ImagePreview(file, vm)
                FileType.VIDEO -> VideoPreview(file, vm)
                else -> CenterMessage("该文件类型暂不支持预览")
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White)
            }
            Text(
                file.name,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun ImagePreview(file: FileItem, vm: PreviewViewModel) {
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
        image != null -> Image(
            image!!, file.name,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            contentScale = ContentScale.Fit
        )
        error != null -> CenterMessage(error!!)
        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(file: FileItem, vm: PreviewViewModel) {
    if (!vm.isRealCard) {
        CenterMessage("模拟模式无法预览真实视频（需真卡）")
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(file.id) {
        val factory = CardFileDataSource.Factory(vm.realFileSystem, file.id)
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("card://preview")))
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
