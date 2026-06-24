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

    /** 播放 [file]；播放结束回调 [onComplete]（用于 UI 复位"播放中"高亮）。失败返回 false。 */
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

    /** 停止并释放（手动停 / 播完 / 离开页面）。 */
    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}
