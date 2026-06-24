package com.example.midun.data

import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.real.RealUsbManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 安全卡门面。App 只面向真卡（FSShell），本类把 [UsbCardOps] 业务方法 / 设备状态 / USB 插拔事件统一转发到
 * [RealUsbManager]，并在 wipe 成功后清共享聊天/日志仓库（真卡的文件清在 [RealUsbManager]）。
 *
 * 历史：曾持有 Mock/Real 两套实现 + `useRealCard` 运行时开关（模拟器/无卡开发流），2026-06-24 砍掉模拟模式
 * 后收成真卡单路径，见 docs/design-deviations.md。
 */
@Singleton
class SecurityCardManager @Inject constructor(
    private val real: RealUsbManager,
    // wipe 成功后清共享聊天/日志仓库（写穿空到卡 / 清内存明文）。
    private val chatRepo: ChatRepository,
    private val operationLog: OperationLogRepository
) : UsbCardOps {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val deviceStatus: StateFlow<DeviceInfo> = real.deviceStatus

    // —— UsbCardOps 路由（全转发真卡）——
    override suspend fun initDevice(password: String, bindDevice: Boolean) =
        real.initDevice(password, bindDevice)

    override suspend fun authenticate(password: String) = real.authenticate(password)
    override fun verifyPassword(password: String) = real.verifyPassword(password)
    override fun logout() = real.logout()

    /**
     * 恢复出厂 / 一键清理：[RealUsbManager] 清卡内文件（wipeUserData）或 SFFormat 强擦（wipeAll），
     * 此处补清共享聊天/日志仓库（写穿空到卡 / 内存）。
     */
    override suspend fun wipeAll() = real.wipeAll().also { if (it.isSuccess) clearRepos() }
    override suspend fun wipeUserData() =
        real.wipeUserData().also { if (it.isSuccess) { clearRepos(); real.refreshCapacity() } }

    private fun clearRepos() {
        chatRepo.clear()
        operationLog.clear()
    }

    override suspend fun updateBinding(bind: Boolean): Result<Unit> = real.updateBinding(bind)
    override suspend fun updateKey() = real.updateKey()

    // —— USB 插拔事件路由（来自 MainActivity 广播，经 DeviceViewModel）——
    fun onUsbAttached() {
        scope.launch { real.connectUsb() }
    }

    fun onUsbDetached() {
        scope.launch { real.closeDevice() }
    }

    /** 真卡序列号（诊断 / 后续 P2P deviceSn）。 */
    fun realSerialNumber(): String? = real.getSerialNumber()

    /** 文件增删后刷新「已用空间」（重读 SFGetCapacity → deviceStatus）。 */
    fun refreshCapacity() = real.refreshCapacity()
}
