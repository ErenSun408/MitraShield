package com.example.midun.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 解码图片字节并按 [reqMaxPx]（最长边目标像素）下采样，避免大图 OOM。隐私文件夹图片的缩略图
 * 与全屏预览共用同一解码路径。解码失败回 null。
 */
fun decodeSampledBitmap(bytes: ByteArray, reqMaxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / sample > reqMaxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
