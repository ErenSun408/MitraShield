package com.example.midun.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.mock.MockUsbManager
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
    private val mockUsbManager: MockUsbManager
) : ViewModel() {

    val deviceStatus = mockUsbManager.deviceStatus

    val isUsbConnected: StateFlow<Boolean> = deviceStatus.map {
        it.status != UsbDeviceStatus.DISCONNECTED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isAuthenticated: StateFlow<Boolean> = deviceStatus.map {
        it.status == UsbDeviceStatus.AUTHENTICATED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onUsbAttached(device: UsbDevice?) {
        mockUsbManager.simulateInsert()
    }

    fun onUsbDetached() {
        mockUsbManager.simulateRemove()
        clearSensitiveMemory()
    }

    fun logout() {
        mockUsbManager.logout()
    }

    /**
     * 忘记密码 → 擦卡重置。wipeAll 完成后才调 onComplete（通常用于导航）。
     * 关键：必须等 wipeAll 跑完再导航——若先导航 popUpTo(0) 销毁本 VM，viewModelScope
     * 会被取消，wipeAll 卡在 delay 处擦除不完整。
     */
    fun wipeAndReset(onComplete: () -> Unit) {
        viewModelScope.launch {
            mockUsbManager.wipeAll()
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
            mockUsbManager.authenticate(password)
                .onSuccess {
                    mockUsbManager.wipeUserData()
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
            mockUsbManager.authenticate(password)
                .onSuccess {
                    mockUsbManager.wipeAll()
                    onSuccess()
                }
                .onFailure { onError("密码错误") }
        }
    }

    // M10 hook: replace with SFCloseDisk() once the real FSShell SDK lands.
    private fun clearSensitiveMemory() {
    }

    fun debugToggleUsb() {
        if (isUsbConnected.value) mockUsbManager.simulateRemove()
        else mockUsbManager.simulateInsert()
    }

    fun debugSimulateFirstInsert() {
        mockUsbManager.simulateFirstInsert()
    }

    fun debugSimulateInitializedInsert() {
        mockUsbManager.simulateInsert()
    }

    private var inactivityJob: Job? = null

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            mockUsbManager.logout()
        }
    }

    fun onAppBackground() {
        if (isAuthenticated.value) resetInactivityTimer()
    }

    fun onAppForeground() {
        inactivityJob?.cancel()
    }

    private companion object {
        const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L
    }
}