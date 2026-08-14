package com.example.midun.network

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 邀请码识别率的量化验证（`[qr]` 2026-08-14，为「载荷混淆」这次改动做的回归）。
 *
 * **为什么要在 JVM 上复刻一遍管线**：把载荷从 203 字节明文换成约 260 字符密文，二维码会大一到两个版本
 * ——码更密即更难拍，而这条链路 8 月 11/12 刚为「识别不出」折腾了两轮。真机一次只能试几十张、还混着
 * 拍摄条件，分不清是码的问题还是手抖；这里把**渲染与解码两端都按生产代码 1:1 复刻**，跑上百张随机载荷，
 * 单独量出「码本身可不可解」这一项。
 *
 * 复刻的是（与 `QrCodeScreen` 保持一致，改那边记得同步改这里）：
 * - 生成：ZXing `Encoder.encode`，纠错 **M**，字符集 UTF-8，每模块 10px，静区 4 模块；
 * - 解码：**PURE_BARCODE 在前、场景启发式在后**，每轮各试 Hybrid 与 GlobalHistogram 两种二值化，共四次。
 *
 * 载荷用 `javax.crypto` + `java.util.Base64` 现搭一份（生产走 `android.util.Base64`，JVM 单测里是桩），
 * 产出的字符串在**长度与字符集上与生产逐字节等价**——而二维码只关心这两件事。
 */
class InvitePayloadQrTest {

    private val rnd = Random(20260814) // 固定种子：失败可复现
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // —— 生产参数（与 QrCodeScreen 同值）——
    private val modulePx = 10
    private val quietModules = 4

    /** 一份形状真实的内层 JSON：SN + 33 字节压缩公钥的 Base64 + 13 位毫秒时间戳 + 一到两个地址。 */
    private fun sampleJson(withSidAndVer: Boolean): String {
        val sn = if (rnd.nextBoolean()) {
            "DEV-%08x".format(rnd.nextInt())
        } else {
            (1..16).map { "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".random(kotlin.random.Random(rnd.nextInt())) }.joinToString("")
        }
        val tpk = Base64.getEncoder().encodeToString(ByteArray(33).also { rnd.nextBytes(it) })
        val exp = System.currentTimeMillis() + 120_000
        val v6 = "240e:%x:%x:%x:%x:%x:%x:%x".format(
            rnd.nextInt(0xffff), rnd.nextInt(0xffff), rnd.nextInt(0xffff),
            rnd.nextInt(0xffff), rnd.nextInt(0xffff), rnd.nextInt(0xffff), rnd.nextInt(0xffff)
        )
        val v4 = "192.168.${rnd.nextInt(255)}.${rnd.nextInt(255)}"
        val addrs = if (rnd.nextInt(4) == 0) """["$v6"]""" else """["$v6","$v4"]"""
        val head = if (withSidAndVer) """"ver":2,"sn":"$sn","sid":"%08x",""".format(rnd.nextInt()) else """"sn":"$sn","""
        return """{$head"tpk":"$tpk","exp":$exp,"addrs":$addrs}"""
    }

    /** 新格式载荷：base64url( IV(12) ‖ AES-GCM(版本字符 ‖ JSON) )，与 `ConnectionInfo.payloadOf` 同构。 */
    private fun newPayload(): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
        val body = cipher.doFinal(('3' + sampleJson(withSidAndVer = false)).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + body)
    }

    /** 旧格式载荷：裸 JSON（含 ver/sid），即 8 月 14 日之前二维码里的原文。 */
    private fun oldPayload(): String = sampleJson(withSidAndVer = true)

    private class Rendered(val pixels: IntArray, val width: Int, val version: Int)

    /** 按生产同参渲染成像素阵（白底黑码 + 静区），并记下 QR 版本号。 */
    private fun render(content: String): Rendered {
        val code = Encoder.encode(content, ErrorCorrectionLevel.M, mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        val matrix = code.matrix!!
        val quiet = quietModules * modulePx
        val side = matrix.width * modulePx + quiet * 2
        val px = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() != 1) continue
                for (dy in 0 until modulePx) {
                    val row = (quiet + y * modulePx + dy) * side
                    for (dx in 0 until modulePx) px[row + quiet + x * modulePx + dx] = 0xFF000000.toInt()
                }
            }
        }
        return Rendered(px, side, code.version.versionNumber)
    }

    /** 生产同款四次阶梯：PURE_BARCODE 在前、场景在后，各配两种二值化。 */
    private fun decode(pixels: IntArray, side: Int): String? {
        val source = RGBLuminanceSource(side, side, pixels)
        val candidates = listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))
        val ladders = listOf(
            mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.TRY_HARDER to true),
            mapOf(DecodeHintType.TRY_HARDER to true)
        )
        for (hints in ladders) {
            for (candidate in candidates) {
                runCatching { QRCodeReader().decode(candidate, hints).text }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /**
     * **这里没有 JPEG 那一轮**：`javax.imageio` / `java.awt` 不在 Android 单元测试的 classpath 上
     * （编译走 android.jar 桩）。「微信压过一手还认不认」由 `tools/qr_bench/QrJpegBench.java` 在桌面 JVM 上
     * 单跑（用法与 2026-08-14 的实测数字见该目录的 README）。本测试守的是「码本身可不可解」这一项。
     */
    private fun run(label: String, count: Int, quality: Float?, payload: () -> String): Int {
        var failures = 0
        var chars = 0
        val versions = sortedMapOf<Int, Int>()
        var side = 0
        repeat(count) {
            val content = payload()
            chars += content.length
            val r = render(content)
            side = r.width
            versions.merge(r.version, 1, Int::plus)
            if (decode(r.pixels, r.width) != content) failures++
        }
        println(
            "[$label] 失败 $failures/$count｜均长 ${chars / count} 字符｜版本分布 $versions｜出图 ${side}×${side}px" +
                (quality?.let { "｜JPEG q=$it" } ?: "")
        )
        return failures
    }

    @Test
    fun newPayload_survivesRenderAndDecode() {
        assertEquals(0, run("新格式·密文", 300, null) { newPayload() })
    }

    /** 旧格式基线：同一套管线跑一遍改动前的裸 JSON，两个数字并排才说明得了「有没有变差」。 */
    @Test
    fun oldPayload_baseline() {
        assertEquals(0, run("旧格式·明文", 300, null) { oldPayload() })
    }
}
