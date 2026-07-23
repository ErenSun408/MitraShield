package com.example.midun.data

import com.example.midun.data.model.OperationLog
import com.example.midun.data.model.OperationType
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.data.real.OperationLogStore
import com.example.midun.data.real.RealUsbManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 操作日志仓库（v4 外增量，见 docs/design-deviations.md「M9.3」）。
 *
 * 以 [StateFlow] 暴露，首页可响应式刷新。最新记录在头部，上限 [MAX_ENTRIES] 条。
 *
 * **持久化（M11.5.4）**：经 [OperationLogStore] 落安全卡隐藏区。内存态空启动；真卡认证成功即从卡加载历史、
 * 每次变更写穿到卡；未认证时 store no-op（仅内存，进程重启即清空，贴合「不留痕」）。
 */
@Singleton
class OperationLogRepository @Inject constructor(
    private val store: OperationLogStore,
    realUsbManager: RealUsbManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _logs = MutableStateFlow<List<OperationLog>>(emptyList())
    val logs: StateFlow<List<OperationLog>> = _logs.asStateFlow()

    /**
     * 卡 IO 串行锁 + 「卡上历史已加载」标志，一起堵住登录瞬间的加载/写入竞态：
     *
     * 登出时内存被清空，登录后 [store.load] 走 USB 读卡（慢），而 `AuthViewModel` 在认证返回后**立刻**记
     * 一条「登录成功」（纯内存，快）。落盘是**整表覆盖**，所以若这条记录抢在加载之前 [persist]，卡上整份
     * 历史会被「只有登录一条」的半截内存顶掉，且不可恢复；反过来若加载后盲赋值，则这条记录被冲掉。
     */
    private val ioLock = Mutex()

    @Volatile
    private var loaded = false

    init {
        // 真卡认证成功（盘已打开）→ 从卡加载历史日志；锁定/拔卡（离开 AUTHENTICATED）→ 清内存明文。
        // wasAuthed 守卫：初始 false 的首个发射不触发清理，仅真正离开认证态才清（M11.6.3 安全加固）。
        scope.launch {
            var wasAuthed = false
            realUsbManager.deviceStatus
                .map { it.status == UsbDeviceStatus.AUTHENTICATED }
                .distinctUntilChanged()
                .collect { authed ->
                    if (authed) {
                        com.example.midun.data.real.CardPerf.time("OperationLogRepository.load(日志侧车)") {
                            ioLock.withLock {
                                val fromCard = store.load()
                                // 合并而非覆盖：加载期间记下的条目（典型=「登录成功」）必须保留。
                                if (fromCard != null) _logs.update { pending -> merge(pending, fromCard) }
                                loaded = true
                            }
                        }
                        persist() // 加载期间的条目此前被压着没落盘，合并后统一写回
                        wasAuthed = true
                    } else if (wasAuthed) {
                        loaded = false
                        _logs.value = emptyList() // 已写穿到卡，重认证后重载
                        wasAuthed = false
                    }
                }
        }
    }

    /** 按时间倒序合并两份日志并截到上限（最新在前）。 */
    private fun merge(pending: List<OperationLog>, fromCard: List<OperationLog>): List<OperationLog> =
        (pending + fromCard).sortedByDescending { it.timestamp }.take(MAX_ENTRIES)

    /** 追加一条记录（最新在前，超出上限丢弃最旧），并写穿持久化。 */
    fun record(type: OperationType, description: String) {
        val entry = OperationLog(type, description, System.currentTimeMillis())
        _logs.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        persist()
    }

    /** 清空日志。由一键清理 / 恢复出厂触发（SecurityCardManager.wipeUserData / wipeAll）。 */
    fun clear() {
        _logs.value = emptyList()
        loaded = true // 历史是被主动擦掉的，没有可保护的旧内容 → 允许这次空表落盘
        persist()
    }

    /**
     * 写穿到隐藏区（store 内部判活动态：未认证 no-op、认证态整表覆盖写）。
     * 卡上历史加载完成前**不落盘**——此时内存不是完整视图，整表覆盖会抹掉卡上历史。
     */
    private fun persist() {
        if (!loaded) return
        scope.launch { ioLock.withLock { store.save(_logs.value) } }
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
