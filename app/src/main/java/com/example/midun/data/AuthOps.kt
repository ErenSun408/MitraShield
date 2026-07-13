package com.example.midun.data

import com.example.midun.data.model.DeviceInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * 退出自动清理偏好（存卡内 `0:/.midun_exitclear.json`，随卡走）。开启后**每次登录认证成功、进主界面前**
 * 自动清空对应数据（用完即清）。物理拔卡时卡已断开无法删卡上数据、`onTaskRemoved` 时间窗口不可靠删大量文件，
 * 故按用户决策落地为「下次登录补清」而非「退出即清」，语义上等价于「每次进来都是干净的」。
 */
data class ExitClearPrefs(
    /** 清空所有联系人与聊天记录。 */
    val clearContacts: Boolean = false,
    /** 清空隐私文件夹内所有文件。 */
    val clearFiles: Boolean = false
)

/**
 * USB 安全卡业务操作接口（M11.3）。由 `RealUsbManager`（真卡 FSShell）实现，`AccountManager`
 * facade 转发到它。USB 连接靠系统插拔广播 + `RealUsbManager.connectUsb`（非接口方法）。
 */
interface AuthOps {
    /** 设备状态流（连接/认证态、设备 ID、绑定、是否已初始化）。 */
    val deviceStatus: StateFlow<DeviceInfo>

    /** 初始化账户：注册手机号 + 登录密码（+ 可选绑定本机）。手机号为本地账户标识（NC7），密码经 PBKDF2 存哈希。 */
    suspend fun initDevice(phone: String, password: String, bindDevice: Boolean): Result<Unit>

    /** 登录认证：校验手机号 + 密码（二者皆对才通过），随后解锁 keystore、载入 DEK。 */
    suspend fun authenticate(phone: String, password: String): Result<Unit>

    /**
     * 校验密码（**不重开盘**）。登录后对敏感操作（改密钥/绑定/一键清理/恢复出厂）二次确认用。
     * 真卡 = 比对会话内存里的 `sha256(登录密码)`（再调 `SFOpenDiskEx` 会因盘已开而失败误判）；Mock = 比对存的密码。
     */
    fun verifyPassword(password: String): Boolean

    /** 退出登录（不拔卡）。真卡 = 关盘但保留 USB 句柄，状态退回 CONNECTED。 */
    fun logout()

    /** 恢复出厂 / 整卡擦除（清密码、文件、聊天、绑定）。 */
    suspend fun wipeAll(): Result<Unit>

    /** 一键清理用户数据（清文件 + 聊天，保留登录态/密码/绑定）。 */
    suspend fun wipeUserData(): Result<Unit>

    /** 切换设备绑定（bind=true 写本机 ID，false 解绑）。真卡写/删卡内 `0:/.bind` → `suspend`（IO）。写卡失败如实返回。 */
    suspend fun updateBinding(bind: Boolean): Result<Unit>

    /** 密钥更新（M12.6 App 层 KEK 轮换）：重生成 KEK、重包不变的 DEK、覆盖卡内 keystore。失败如实返回。 */
    suspend fun updateKey(): Result<Unit>

    /** 读退出自动清理偏好（卡内 `0:/.midun_exitclear.json`）。需盘已打开。缺失/失败回默认全 false。 */
    suspend fun getExitClearPrefs(): ExitClearPrefs

    /** 写退出自动清理偏好到卡。需盘已打开。写卡失败如实返回。 */
    suspend fun setExitClearPrefs(prefs: ExitClearPrefs): Result<Unit>
}
