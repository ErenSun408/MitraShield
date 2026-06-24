package com.example.midun.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.SettingsStore
import com.example.midun.data.ChatRepository
import com.example.midun.data.model.UsbDeviceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val cardManager: SecurityCardManager,
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

    val deviceStatus = cardManager.deviceStatus

    val isUsbConnected: StateFlow<Boolean> = deviceStatus.map {
        it.status != UsbDeviceStatus.DISCONNECTED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isAuthenticated: StateFlow<Boolean> = deviceStatus.map {
        it.status == UsbDeviceStatus.AUTHENTICATED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onUsbAttached(device: UsbDevice?) {
        cardManager.onUsbAttached()
    }

    fun onUsbDetached() {
        cardManager.onUsbDetached()
        clearSensitiveMemory()
    }

    fun logout() {
        cardManager.logout()
    }

    /**
     * 忘记密码 → 擦卡重置。wipeAll 完成后才调 onComplete（通常用于导航）。
     * 关键：必须等 wipeAll 跑完再导航——若先导航 popUpTo(0) 销毁本 VM，viewModelScope
     * 会被取消，wipeAll 卡在 delay 处擦除不完整。
     */
    fun wipeAndReset(onComplete: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cardManager.wipeAll()
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
            if (cardManager.verifyPassword(password)) {
                cardManager.wipeUserData()
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
            if (!cardManager.verifyPassword(password)) {
                onError("密码错误")
                return@launch
            }
            cardManager.wipeAll()
                .onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: "恢复出厂失败") }
        }
    }

    /**
     * 切换设备绑定状态（M7.3 patch §M7 改动2）：校验密码 → 翻转 boundPhoneId → onSuccess。
     * mock 期不影响登录流程（authenticate 仍仅校验密码）；真 SDK 后将耦合 boundPhoneId 校验。
     */
    fun updateBinding(password: String, bind: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (cardManager.verifyPassword(password)) {
                cardManager.updateBinding(bind)
                onSuccess()
            } else onError("密码错误")
        }
    }

    /**
     * 密钥更新（M12.6）：校验密码 → App 层 KEK 轮换（[SecurityCardManager.updateKey] 重生成 KEK 重包不变的
     * DEK、覆盖卡内 keystore）→ 按 **Result 分流**。修掉旧实现「吞掉 Result、密码对就无条件报成功」的假实现：
     * 轮换失败（盘问题/写卡失败）如实报错，不再对用户撒谎。
     */
    fun updateKey(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            if (!cardManager.verifyPassword(password)) {
                onError("密码错误")
                return@launch
            }
            cardManager.updateKey()
                .onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: "密钥更新失败") }
        }
    }

    /**
     * 拔卡敏感数据清理（M11.6.3）：真卡 `SFCloseDisk` 已由 `cardManager.onUsbDetached()`→`real.closeDevice()`
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
            cardManager.logout()
        }
    }

    fun onAppBackground() {
        if (isAuthenticated.value) resetInactivityTimer()
    }

    fun onAppForeground() {
        inactivityJob?.cancel()
    }
}