package com.example.midun.data.model

enum class UsbDeviceStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

data class DeviceInfo(
    val isInitialized: Boolean = false,
    val deviceId: String = "",
    val status: UsbDeviceStatus = UsbDeviceStatus.DISCONNECTED,
    val boundPhoneId: String? = null
)