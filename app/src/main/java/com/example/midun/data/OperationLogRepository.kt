package com.example.midun.data

import com.example.midun.data.model.OperationLog
import com.example.midun.data.model.OperationType
import com.example.midun.data.model.SessionStatus
import com.example.midun.data.local.LocalAuthManager
import com.example.midun.data.local.OperationLogStore
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

/**
 * 操作日志仓库（v4 外增量，见 docs/design-deviations.md「M9.3」）。
 *
 * 以 [StateFlow] 暴露，首页可响应式刷新。最新记录在头部，上限 [MAX_ENTRIES] 条。
 *
 * **持久化**：经 [OperationLogStore] 落本地隐私库。内存态空启动；登录认证成功即从本地加载历史、
 * 每次变更写穿到盘；未认证时 store no-op（仅内存，进程重启即清空，贴合「不留痕」）。
 */
@Singleton
class OperationLogRepository @Inject constructor(
    private val store: OperationLogStore,
    authManager: LocalAuthManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _logs = MutableStateFlow<List<OperationLog>>(emptyList())
    val logs: StateFlow<List<OperationLog>> = _logs.asStateFlow()

    init {
        // 登录认证成功 → 从本地加载历史日志；登出（离开 AUTHENTICATED）→ 清内存明文。
        // wasAuthed 守卫：初始 false 的首个发射不触发清理，仅真正离开认证态才清。
        scope.launch {
            var wasAuthed = false
            authManager.deviceStatus
                .map { it.status == SessionStatus.AUTHENTICATED }
                .distinctUntilChanged()
                .collect { authed ->
                    if (authed) {
                        store.load()?.let { _logs.value = it }
                        wasAuthed = true
                    } else if (wasAuthed) {
                        _logs.value = emptyList() // 已写穿到卡，重认证后重载
                        wasAuthed = false
                    }
                }
        }
    }

    /** 追加一条记录（最新在前，超出上限丢弃最旧），并写穿持久化。 */
    fun record(type: OperationType, description: String) {
        val entry = OperationLog(type, description, System.currentTimeMillis())
        _logs.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        persist()
    }

    /** 清空日志。由一键清理 / 恢复出厂触发（SecurityCardManager.wipeUserData / wipeAll）。 */
    fun clear() {
        _logs.value = emptyList()
        persist()
    }

    /** 写穿到隐藏区（store 内部判活动态：未认证 no-op、认证态整表覆盖写）。 */
    private fun persist() {
        scope.launch { store.save(_logs.value) }
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
