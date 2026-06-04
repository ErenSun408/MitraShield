package com.example.midun.data.mock

import com.example.midun.data.model.OperationLog
import com.example.midun.data.model.OperationType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存版操作日志（v4 外增量，见 docs/design-deviations.md「M9.3」）。
 *
 * 以 [StateFlow] 暴露，使首页可响应式刷新。最新记录在列表头部，上限 [MAX_ENTRIES] 条。
 * mock 期不持久化：进程重启即清空——既因无卡存储，也贴合「不留痕」哲学。
 * M11 接真实 SDK 时改为读写安全卡 EMMC（聊天记录同款存储，见 v4 验收）。
 */
@Singleton
class MockOperationLog @Inject constructor() {

    private val _logs = MutableStateFlow<List<OperationLog>>(emptyList())
    val logs: StateFlow<List<OperationLog>> = _logs.asStateFlow()

    /** 追加一条记录（最新在前，超出上限丢弃最旧）。 */
    fun record(type: OperationType, description: String) {
        val entry = OperationLog(type, description, System.currentTimeMillis())
        _logs.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    /** 清空日志。由一键清理 / 恢复出厂触发（MockUsbManager.wipeUserData / wipeAll）。 */
    fun clear() {
        _logs.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
