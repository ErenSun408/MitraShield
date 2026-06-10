package com.example.midun.data

import com.example.midun.data.model.DeviceInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * USB 安全卡业务操作接口（M11.3）。`MockUsbManager`（模拟）与 `RealUsbManager`（真卡 FSShell）
 * 都实现它，由 `SecurityCardManager` facade 按运行时开关路由。
 *
 * **不含 DEV 专属方法**（`simulateInsert/Remove/FirstInsert`）——那些是 Mock 模拟插拔卡用的，
 * 真卡靠 USB 广播 + `RealUsbManager.connectUsb`，故只 Mock 有、不进接口。
 */
interface UsbCardOps {
    /** 设备状态流（连接/认证态、设备 ID、绑定、是否已初始化）。 */
    val deviceStatus: StateFlow<DeviceInfo>

    /** 初始化设备：设置登录密码（+ 可选绑定本机）。真卡 = 把默认密码改成用户密码哈希。 */
    suspend fun initDevice(password: String, bindDevice: Boolean): Result<Unit>

    /** 密码认证（登录）。真卡 = `SFOpenDiskEx(diskName, sha256(password))`，打开盘即认证。 */
    suspend fun authenticate(password: String): Result<Unit>

    /** 退出登录（不拔卡）。真卡 = 关盘但保留 USB 句柄，状态退回 CONNECTED。 */
    fun logout()

    /** 恢复出厂 / 整卡擦除（清密码、文件、聊天、绑定）。 */
    suspend fun wipeAll(): Result<Unit>

    /** 一键清理用户数据（清文件 + 聊天，保留登录态/密码/绑定）。 */
    suspend fun wipeUserData(): Result<Unit>

    /** 切换设备绑定（bind=true 写本机 ID，false 解绑）。真卡写卡内 `0:/.bind` → `suspend`（IO）。 */
    suspend fun updateBinding(bind: Boolean)

    /** 密钥更新（轮换）。 */
    suspend fun updateKey(): Result<Unit>
}
