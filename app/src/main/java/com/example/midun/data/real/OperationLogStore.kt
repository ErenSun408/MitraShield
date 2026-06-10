package com.example.midun.data.real

import com.example.midun.data.model.OperationLog
import com.example.midun.data.model.OperationType
import com.example.midun.data.model.UsbDeviceStatus
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 操作日志的真卡持久化后端（M11.5.4）。把 [com.example.midun.data.mock.MockOperationLog] 的内存日志
 * 序列化为隐藏区侧车 [LOG_PATH] 的 JSON，使日志跨会话/重启留存于安全卡。
 *
 * **活动条件 = [RealUsbManager] 认证态**：真卡只在 `useRealCard` 且盘已打开（`SFOpenDiskEx` 成功）时
 * 进入 AUTHENTICATED，故此即「真卡模式 + 隐藏区可读写」的精确判据。**有意不注入 `SecurityCardManager`**
 * ——它经 `MockUsbManager` 反向依赖 `MockOperationLog`，注入会成 DI 环；`RealUsbManager` 是叶子、无环。
 * 非活动态（模拟模式 / 未认证）下 [load] 回 null、[save] no-op，保留 mock 内存日志开发流。
 */
@Singleton
class OperationLogStore @Inject constructor(
    private val real: RealFileSystem,
    private val realUsb: RealUsbManager
) {
    private fun active(): Boolean =
        realUsb.deviceStatus.value.status == UsbDeviceStatus.AUTHENTICATED

    /** 真卡模式从卡读历史日志；非活动态回 null（调用方保留内存）；文件不存在视为空列表。 */
    suspend fun load(): List<OperationLog>? = withContext(Dispatchers.IO) {
        if (!active()) return@withContext null
        runCatching {
            val out = ByteArrayOutputStream()
            if (real.readFile(LOG_PATH, out).isFailure) return@runCatching emptyList()
            fromJson(out.toString(Charsets.UTF_8.name()))
        }.getOrNull()
    }

    /** 真卡模式写穿到卡（整表覆盖写）；非活动态 no-op。 */
    suspend fun save(logs: List<OperationLog>) = withContext(Dispatchers.IO) {
        if (!active()) return@withContext
        runCatching { real.writeFile(LOG_PATH, toJson(logs).byteInputStream()) }
        Unit
    }

    private fun toJson(logs: List<OperationLog>): String {
        val arr = JSONArray()
        logs.forEach { l ->
            arr.put(JSONObject().put("type", l.type.name).put("desc", l.description).put("ts", l.timestamp))
        }
        return JSONObject().put("logs", arr).toString()
    }

    private fun fromJson(text: String): List<OperationLog> {
        val arr = JSONObject(text).optJSONArray("logs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = runCatching { OperationType.valueOf(o.getString("type")) }.getOrNull()
                ?: return@mapNotNull null
            OperationLog(type, o.optString("desc"), o.optLong("ts"))
        }
    }

    private companion object {
        const val LOG_PATH = "0:/.midun_oplog.json"
    }
}
