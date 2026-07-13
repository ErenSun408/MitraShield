package com.example.midun.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无卡版设备身份（NC1.1）。真卡版用安全卡 `SFDiskGetSN` 作全球唯一 deviceSn；无卡版没有卡，
 * 改为**首次运行生成一枚 UUID 持久化到 App 私有目录**，之后每次读同一个，供 P2P `deviceSn`（二维码
 * 弱来源标识 / 联系人去重）使用。
 *
 * **稳定性**：id 存 `filesDir/.device_id`，跟随 App 数据。卸载重装 / 恢复出厂（清 `filesDir`）会换新 id——
 * 与真卡「SN 随卡走」不同，这是无卡版的固有取舍（对端会视为新设备）。
 *
 * **格式**：`DEV-` + 32 位 hex（去掉 UUID 连字符），与 [com.example.midun.network.P2PSessionManager]
 * 的 `fallbackSn`（`DEV-xxxx`）风格一致，便于日志辨识。
 */
@Singleton
class LocalDeviceIdentity @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    /** 进程内缓存，避免每次取用都读盘。首次访问时读盘或生成。 */
    @Volatile
    private var cached: String? = null

    /** 本机设备 id（稳定、持久）。首次调用生成并落盘，之后返回同一值。线程安全。 */
    fun deviceId(): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    private fun load(): String {
        runCatching {
            if (file.exists()) {
                val existing = file.readText().trim()
                if (existing.isNotEmpty()) return existing
            }
        }
        val generated = "DEV-" + UUID.randomUUID().toString().replace("-", "")
        runCatching { file.writeText(generated) }
        return generated
    }

    private companion object {
        const val FILE_NAME = ".device_id"
    }
}
