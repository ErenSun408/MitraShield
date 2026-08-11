package com.example.midun.audio

import android.media.MediaPlayer
import java.io.File

/**
 * 语音播放器（`[chat-voice]`）。一次只放一段：再次 [play] 会先停掉上一段。播放卡内缓存语音前，调用方先把
 * 卡内字节读出落到 App cache 的临时文件（语音 clip 小、整读即可），这里只负责播该临时文件。
 *
 * 由 ChatDetailScreen 用 `remember` 持有单实例，离开页面时 [stop]。
 */
class VoicePlayer {

    private var player: MediaPlayer? = null

    /** 播放 [file] **从头开始**；播放结束回调 [onComplete]（用于 UI 复位"播放中"高亮）。失败返回 false。 */
    fun play(file: File, onComplete: () -> Unit): Boolean {
        stop()
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    onComplete()
                    this@VoicePlayer.stop()
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    /**
     * 暂停，**保留播放器与播放位置**供 [resume] 接着放（客户需求 2026-08-12：暂停后再点应续播，不是重头来）。
     * 原先「暂停」其实是 [stop]——播放器当场释放，位置无从谈起，所以每次都从头。返回是否真的暂停成功。
     */
    fun pause(): Boolean {
        val p = player ?: return false
        return runCatching {
            if (p.isPlaying) { p.pause(); true } else false
        }.getOrDefault(false)
    }

    /** 从暂停处继续。播放器已被释放（如中途播了别的语音）时返回 false，由调用方退回从头播。 */
    fun resume(): Boolean {
        val p = player ?: return false
        return runCatching {
            if (!p.isPlaying) { p.start(); true } else false
        }.getOrDefault(false)
    }

    /** 停止并释放（播完 / 换一段 / 离开页面）。**暂停不要调这个**，它会丢掉播放位置。 */
    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}
