package com.example.midun.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.SettingsStore
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
    private val settingsStore: SettingsStore
) : ViewModel() {

    val deviceStatus = cardManager.deviceStatus

    /** 真卡/模拟模式开关（M11.3）：DevControlPanel 切换；切到真卡即尝试连接已插入的卡。 */
    val useRealCard = cardManager.useRealCard
    fun setUseRealCard(useReal: Boolean) = cardManager.setUseRealCard(useReal)
    fun realSerialNumber(): String? = cardManager.realSerialNumber()

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
    fun wipeAndReset(onComplete: () -> Unit) {
        viewModelScope.launch {
            cardManager.wipeAll()
            onComplete()
        }
    }

    /**
     * 一键清理用户数据（M7.2 patch §M7 改动1 之外的用户决策）：先校验密码，通过后清
     * fileSystem + chatRepository。保留登录态、初始化态、绑定与密码。回调成功/错误，
     * 由 UI 关闭弹框或显示"密码错误"。
     */
    fun wipeUserData(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cardManager.authenticate(password)
                .onSuccess {
                    cardManager.wipeUserData()
                    onSuccess()
                }
                .onFailure { onError("密码错误") }
        }
    }

    /**
     * 恢复出厂（M7.2 patch §M7 改动1）：校验密码 → wipeAll → onSuccess。由 UI 在
     * onSuccess 里 navigate(Init){popUpTo(0)}；与 [wipeAndReset] 的区别仅是入口（前者
     * 来自 Settings 危险操作，后者来自 Login 忘记密码）+ 多一道密码校验。
     */
    fun factoryReset(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cardManager.authenticate(password)
                .onSuccess {
                    cardManager.wipeAll()
                    onSuccess()
                }
                .onFailure { onError("密码错误") }
        }
    }

    /**
     * 切换设备绑定状态（M7.3 patch §M7 改动2）：校验密码 → 翻转 boundPhoneId → onSuccess。
     * mock 期不影响登录流程（authenticate 仍仅校验密码）；真 SDK 后将耦合 boundPhoneId 校验。
     */
    fun updateBinding(password: String, bind: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cardManager.authenticate(password)
                .onSuccess {
                    cardManager.updateBinding(bind)
                    onSuccess()
                }
                .onFailure { onError("密码错误") }
        }
    }

    /**
     * 密钥更新（M7.4 patch §M7 改动3）：校验密码 → 调安全卡密钥轮换（mock：delay 1s）→ onSuccess。
     * 真 SDK 接入后历史文件仍可用旧会话密钥解密，新生成的二级密钥不暴露给上层。
     */
    fun updateKey(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cardManager.authenticate(password)
                .onSuccess {
                    cardManager.updateKey()
                    onSuccess()
                }
                .onFailure { onError("密码错误") }
        }
    }

    /**
     * 拔卡敏感数据清理（M11.6.3）：真卡 `SFCloseDisk` 已由 `cardManager.onUsbDetached()`→`real.closeDevice()`
     * 完成；内存明文（聊天/操作日志）清理由各仓库响应式监听 `deviceStatus` 离开 AUTHENTICATED 自动处理。
     * 此处保留为额外的进程内敏感态清理挂钩（当前无新增项）。
     */
    private fun clearSensitiveMemory() {
    }

    fun debugToggleUsb() {
        if (isUsbConnected.value) cardManager.simulateRemove()
        else cardManager.simulateInsert()
    }

    fun debugSimulateFirstInsert() {
        cardManager.simulateFirstInsert()
    }

    fun debugSimulateInitializedInsert() {
        cardManager.simulateInsert()
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