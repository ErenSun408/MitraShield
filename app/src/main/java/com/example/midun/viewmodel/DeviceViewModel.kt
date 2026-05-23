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