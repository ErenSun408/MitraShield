package com.example.midun.data.mock

import android.content.Context
import android.provider.Settings
import com.example.midun.data.UsbCardOps
import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.model.UsbDeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class MockUsbManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileSystem: MockFileSystem,
    private val chatRepository: MockChatRepository,
    private val operationLog: MockOperationLog
) : UsbCardOps {
    private val _deviceStatus = MutableStateFlow(DeviceInfo())
    override val deviceStatus: StateFlow<DeviceInfo> = _deviceStatus.asStateFlow()

    private var storedPassword: String? = null

    fun simulateInsert() {
        storedPassword = "123456"
        _deviceStatus.value = DeviceInfo(
            isInitialized = true,
            deviceId      = "MOCK_DEVICE_001",
            status        = UsbDeviceStatus.CONNECTED
        )
    }

    fun simulateRemove() {
        storedPassword = null
        _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
    }

    fun simulateFirstInsert() {
        storedPassword = null
        _deviceStatus.value = DeviceInfo(
            isInitialized = false,
            status        = UsbDeviceStatus.CONNECTED
        )
    }

    override suspend fun initDevice(password: String, bindDevice: Boolean): Result<Unit> {
        delay(1500)
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        storedPassword = password
        _deviceStatus.value = _deviceStatus.value.copy(
            isInitialized = true,
            status        = UsbDeviceStatus.AUTHENTICATED,
            boundPhoneId  = if (bindDevice) deviceId else null
        )
        return Result.success(Unit)
    }

    override suspend fun authenticate(password: String): Result<Unit> {
        delay(800)
        return if (password == storedPassword) {
            _deviceStatus.value = _deviceStatus.value.copy(status = UsbDeviceStatus.AUTHENTICATED)
            Result.success(Unit)
        } else {
            Result.failure(Exception("密码错误"))
        }
    }

    override fun logout() {
        _deviceStatus.value = _deviceStatus.value.copy(status = UsbDeviceStatus.CONNECTED)
    }

    override suspend fun wipeAll(): Result<Unit> {
        delay(2000)
        storedPassword = null
        // 整卡擦除：连同卡内文件、聊天与操作日志一并清空，使"恢复出厂/忘记密码"名副其实。
        fileSystem.clear()
        chatRepository.clear()
        operationLog.clear()
        _deviceStatus.value = _deviceStatus.value.copy(
            isInitialized = false,
            boundPhoneId  = null,
            status        = UsbDeviceStatus.CONNECTED
        )
        return Result.success(Unit)
    }

    /**
     * 一键清理用户数据：清隐私文件 + 聊天 + 联系人，**保留**登录态/初始化态/绑定/密码。
     * 与 [wipeAll]（恢复出厂）的关键区别——后者额外清 storedPassword、isInitialized、boundPhoneId
     * 并把 status 退回 CONNECTED，本方法只动数据层。
     */
    override suspend fun wipeUserData(): Result<Unit> {
        delay(1500)
        fileSystem.clear()
        chatRepository.clear()
        operationLog.clear()
        return Result.success(Unit)
    }

    /**
     * 切换绑定状态（M7.3 patch §M7 改动2）：bind=true 写入本机 ANDROID_ID，bind=false 清空。
     * mock 期仅写状态、不真校验：authenticate 仍只用密码，不会因 boundPhoneId 不匹配而拒登。
     * 真 SDK 接入后需在 authenticate 处补 boundPhoneId 校验。
     */
    override fun updateBinding(bind: Boolean) {
        val deviceId = if (bind) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } else null
        _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = deviceId)
    }

    /**
     * 密钥更新（M7.4 patch §M7 改动3）：mock 期无实际密钥状态可轮换，仅 delay 模拟工作时长。
     * 真 SDK 接入后调安全卡密钥更新接口；历史文件由旧密钥派生的会话密钥保留，仍可解密。
     */
    override suspend fun updateKey(): Result<Unit> {
        delay(1000)
        return Result.success(Unit)
    }
}