package com.example.midun.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 语音录制器（`[chat-voice]`，微信式按住说话）。录到 App 私有 cache 的临时 `.m4a`（AAC / MPEG-4），录完把
 * 文件交给文件传输管线发送（手机来源）——发送层会另写一份卡内 `0:/.sent_<id>` 副本供发送方回放，临时文件随后删。
 *
 * 不直接落卡：录制是手机本地行为，落卡/加密统一由发送管线处理（与「手机来源发文件」同一条路径）。
 * 单实例只录一段：再次 [start] 前会清掉上一段残留。线程：UI 线程调用即可（MediaRecorder 内部异步采集）。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    /** 开始录制；成功返回 true（失败=无麦克风权限/设备占用，已自行清理）。 */
    fun start(): Boolean {
        stopQuietly()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setMaxDuration(MAX_DURATION_SEC * 1000) // 硬件侧 60s 封顶，到点自动停止编码
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /** 当前录制时长（秒，向上取整）；UI 实时显示用。未在录返回 0。 */
    fun elapsedSec(): Int =
        if (startedAt == 0L) 0 else (((System.currentTimeMillis() - startedAt) + 999) / 1000).toInt()

    data class Recording(val file: File, val durationSec: Int)

    /** 停止并返回录音文件 + 时长（秒）；失败或过短（<1s）返回 null 并清理临时文件。 */
    fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        val durationMs = System.currentTimeMillis() - startedAt
        recorder = null; outputFile = null; startedAt = 0L
        return try {
            rec.stop()
            rec.release()
            if (file != null && file.exists() && durationMs >= MIN_DURATION_MS) {
                Recording(file, ((durationMs + 999) / 1000).toInt())
            } else {
                file?.delete(); null
            }
        } catch (e: Exception) {
            // stop() 在录制过早结束（无音频数据）时会抛 —— 当作过短处理。
            runCatching { rec.release() }
            file?.delete()
            null
        }
    }

    /** 取消录制并删临时文件（上滑取消 / 离开页面）。 */
    fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null; outputFile = null; startedAt = 0L
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }

    private fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        /** 太短的录音（误触）丢弃阈值。 */
        const val MIN_DURATION_MS = 1000L
        /** 录音时长上限（秒，微信式 60s）。 */
        const val MAX_DURATION_SEC = 60
    }
}
