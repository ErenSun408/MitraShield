package com.example.midun.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.ExitClearPrefs
import com.example.midun.data.AccountManager
import com.example.midun.data.SettingsStore
import com.example.midun.data.ChatRepository
import com.example.midun.data.model.SessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val settingsStore: SettingsStore,
    private val chatRepository: ChatRepository
) : ViewModel() {

    /** 文件预览缓存统计（设置页「清除缓存」副标题）：返回 (文件数, 总字节)。 */
    fun loadCacheStats(onResult: (count: Int, bytes: Long) -> Unit) {
        viewModelScope.launch { chatRepository.cacheStats().let { onResult(it.first, it.second) } }
    }

    /** 清除全部文件预览缓存（只清 .recv_/.sent_ 暂存，不动文件夹/聊天记录）。回调返回释放字节数。 */
    fun clearFileCache(onResult: (freedBytes: Long) -> Unit) {
        viewModelScope.launch { onResult(chatRepository.clearCache()) }
    }

    val deviceStatus = accountManager.deviceStatus

    val isUsbConnected: StateFlow<Boolean> = deviceStatus.map {
        it.status != SessionStatus.DISCONNECTED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isAuthenticated: StateFlow<Boolean> = deviceStatus.map {
        it.status == SessionStatus.AUTHENTICATED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 退出自动清理偏好（卡内持久，下次登录补清）：认证成功后从卡加载当前值供设置页显示。
    private val _exitClearPrefs = MutableStateFlow(ExitClearPrefs())
    val exitClearPrefs: StateFlow<ExitClearPrefs> = _exitClearPrefs.asStateFlow()

    init {
        viewModelScope.launch {
            deviceStatus.collect {
                if (it.status == SessionStatus.AUTHENTICATED) {
                    _exitClearPrefs.value = accountManager.getExitClearPrefs()
                }
            }
        }
    }

    /** 设置「登录时清空联系人」。开启需密码确认（谨慎，会清空所有聊天记录），关闭无需密码。写卡持久。 */
    fun setClearContactsOnExit(enabled: Boolean, password: String?, onSuccess: () -> Unit, onError: (String) -> Unit) =
        updateExitClear(_exitClearPrefs.value.copy(clearContacts = enabled), enabled, password, onSuccess, onError)

    /** 设置「登录时清空隐私文件」。开启需密码确认（谨慎，会清空所有隐私文件），关闭无需密码。写卡持久。 */
    fun setClearFilesOnExit(enabled: Boolean, password: String?, onSuccess: () -> Unit, onError: (String) -> Unit) =
        updateExitClear(_exitClearPrefs.value.copy(clearFiles = enabled), enabled, password, onSuccess, onError)

    private fun updateExitClear(
        target: ExitClearPrefs, enabling: Boolean, password: String?,
        onSuccess: () -> Unit, onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (enabling && (password == null || !accountManager.verifyPassword(password))) {
                onError("密码错误")
                return@launch
            }
            accountManager.setExitClearPrefs(target)
                .onSuccess { _exitClearPrefs.value = target; onSuccess() }
                .onFailure { onError(it.message ?: "设置失败") }
        }
    }

    fun onUsbAttached(device: UsbDevice?) {
        accountManager.onUsbAttached()
    }

    fun onUsbDetached() {
        accountManager.onUsbDetached()
        clearSensitiveMemory()
    }

    fun logout() {
        accountManager.logout()
    }

    /**
     * 忘记密码 → 擦卡重置。wipeAll 完成后才调 onComplete（通常用于导航）。
     * 关键：必须等 wipeAll 跑完再导航——若先导航 popUpTo(0) 销毁本 VM，viewModelScope
     * 会被取消，wipeAll 卡在 delay 处擦除不完整。
     */
    fun wipeAndReset(onComplete: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            accountManager.wipeAll()
                .onSuccess { onComplete() }
                .onFailure { onError(it.message ?: "恢复出厂失败") }
        }
    }

    /**
     * 一键清理用户数据（M7.2 patch §M7 改动1 之外的用户决策）：先校验密码，通过后清
     * fileSystem + chatRepository。保留登录态、初始化态、绑定与密码。回调成功/错误，
     * 由 UI 关闭弹框或显示"密码错误"。
     */
    fun wipeUserData(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (accountManager.verifyPassword(password)) {
                accountManager.wipeUserData()
                onSuccess()
            } else onError("密码错误")
        }
    }

    /**
     * 恢复出厂（M7.2 patch §M7 改动1）：校验密码 → wipeAll → onSuccess。由 UI 在
     * onSuccess 里 navigate(Init){popUpTo(0)}；与 [wipeAndReset] 的区别仅是入口（前者
     * 来自 Settings 危险操作，后者来自 Login 忘记密码）+ 多一道密码校验。
     */
    fun factoryReset(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (!accountManager.verifyPassword(password)) {
                onError("密码错误")
                return@launch
            }
            accountManager.wipeAll()
                .onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: "恢复出厂失败") }
        }
    }

    /**
     * 切换设备绑定状态（M11.6.6）：校验密码 → 写/删卡内 `0:/.bind` → 按 **Result 分流**。
     * 写卡失败如实报错，不再无条件报成功（绑定校验是登录安全门，谎报会让它失效）。
     */
    fun updateBinding(password: String, bind: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (!accountManager.verifyPassword(password)) {
                onError("密码错误")
                return@launch
            }
            accountManager.updateBinding(bind)
                .onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: (if (bind) "绑定失败" else "解绑失败")) }
        }
    }

    /**
     * 密钥更新（M12.6）：校验密码 → App 层 KEK 轮换（[AccountManager.updateKey] 重生成 KEK 重包不变的
     * DEK、覆盖卡内 keystore）→ 按 **Result 分流**。修掉旧实现「吞掉 Result、密码对就无条件报成功」的假实现：
     * 轮换失败（盘问题/写卡失败）如实报错，不再对用户撒谎。
     */
    fun updateKey(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (!accountManager.verifyPassword(password)) {
                onError("密码错误")
                return@launch
            }
            accountManager.updateKey()
                .onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: "密钥更新失败") }
        }
    }

    /**
     * 拔卡敏感数据清理（M11.6.3）：真卡 `SFCloseDisk` 已由 `accountManager.onUsbDetached()`→`real.closeDevice()`
     * 完成；内存明文（聊天/操作日志）清理由各仓库响应式监听 `deviceStatus` 离开 AUTHENTICATED 自动处理。
     * 此处保留为额外的进程内敏感态清理挂钩（当前无新增项）。
     */
    private fun clearSensitiveMemory() {
    }

    private var inactivityJob: Job? = null

    // 自动锁定超时（M7.5 + M11.6.4 持久化）：经 [SettingsStore]（DataStore）存手机本地，重启不丢。
    val inactivityTimeoutMinutes: StateFlow<Int> = settingsStore.inactivityTimeoutMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_TIMEOUT_MIN)

    fun setInactivityTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { settingsStore.setInactivityTimeout(minutes) }
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            delay(inactivityTimeoutMinutes.value * 60_000L)
            accountManager.logout()
        }
    }

    fun onAppBackground() {
        if (isAuthenticated.value) resetInactivityTimer()
    }

    fun onAppForeground() {
        inactivityJob?.cancel()
    }

    private var screenOffJob: Job? = null

    // 息屏自动退出（彻底杀进程）配置：-1 关闭 / 0 立即 / N 秒延迟。存手机本地，重启不丢。
    val screenOffExitSeconds: StateFlow<Int> = settingsStore.screenOffExitSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.SCREEN_OFF_EXIT_OFF)

    fun setScreenOffExitSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setScreenOffExitSeconds(seconds) }
    }

    /**
     * 息屏事件：仅在已认证且功能开启时武装退出。立即档（0 秒）下一 tick 即退；延迟档启动计时器，
     * 期间 [onScreenOn]（亮屏/回前台）会取消。退出动作（finishAndRemoveTask + killProcess）由
     * Activity 经 [onExit] 执行——ViewModel 不持有 Activity 引用。
     */
    fun onScreenOff(onExit: () -> Unit) {
        if (!isAuthenticated.value) return
        val delaySec = screenOffExitSeconds.value
        if (delaySec < 0) return // 关闭
        screenOffJob?.cancel()
        screenOffJob = viewModelScope.launch {
            if (delaySec > 0) delay(delaySec * 1000L)
            onExit()
        }
    }

    fun onScreenOn() {
        screenOffJob?.cancel()
    }
}