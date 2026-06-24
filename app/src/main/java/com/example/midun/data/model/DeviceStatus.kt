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
    val boundPhoneId: String? = null,
    // 真卡容量（M11.6.1，SFGetCapacity 读取；认证成功后填）。0 = 未认证/未知 → UI 显占位。
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
)