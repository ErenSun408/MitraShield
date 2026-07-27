package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import com.example.midun.data.OperationLogRepository
import com.example.midun.data.model.OperationLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * 暴露操作日志给首页「最近操作」卡。直接转发 [OperationLogRepository] 的 StateFlow——
 * 各记录点（AuthViewModel/FileViewModel/ChatViewModel）通过同一 @Singleton 写入，
 * 首页观察此流即可实时刷新，无需手动 reload（区别于 contacts/folders 的 reload 模式）。
 */
@HiltViewModel
class OperationLogViewModel @Inject constructor(
    operationLog: OperationLogRepository
) : ViewModel() {
    val logs: StateFlow<List<OperationLog>> = operationLog.logs

    /** 「最近操作」开关（设置页控制，默认关）：为 false 时首页整块隐藏、后台也不记录。 */
    val enabled: StateFlow<Boolean> = operationLog.enabled
}
