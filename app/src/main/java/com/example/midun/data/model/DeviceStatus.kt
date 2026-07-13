package com.example.midun.data.model

enum class SessionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

data class DeviceInfo(
    val isInitialized: Boolean = false,
    val deviceId: String = "",
    val status: SessionStatus = SessionStatus.DISCONNECTED,
    val boundPhoneId: String? = null,
    // 注册手机号（无卡版本地账户标识，NC7）：connect/init 时从 `.auth` 读出，供登录页预填。未初始化为 null。
    val registeredPhone: String? = null,
    // 隐私库容量（认证成功后填手机存储可用/总量）。0 = 未认证/未知 → UI 显占位。
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
)