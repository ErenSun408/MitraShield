package com.example.midun.data

import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.data.real.RealUsbManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val operationLog: OperationLogRepository,
    // 后台自动登出超时（分钟），见 [onAppBackground]。
    private val settingsStore: SettingsStore
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

    override suspend fun getExitClearPrefs(): ExitClearPrefs = real.getExitClearPrefs()
    override suspend fun setExitClearPrefs(prefs: ExitClearPrefs): Result<Unit> = real.setExitClearPrefs(prefs)

    // —— USB 插拔事件路由（来自 MainActivity 广播，经 DeviceViewModel）——
    fun onUsbAttached(device: android.hardware.usb.UsbDevice?) {
        scope.launch { real.connectUsb(device) }
    }

    /** [detachedName] = 广播里被拔设备的 deviceName（可能为 null）；由 [RealUsbManager.onDeviceDetached] 判是否本卡。 */
    fun onUsbDetached(detachedName: String?) {
        scope.launch { real.onDeviceDetached(detachedName) }
    }

    /**
     * 复核插卡状态（Activity 每次启动时调）：内存说有卡但系统里已经没有 → 同步降级 DISCONNECTED，
     * 再异步释放句柄 / 清 DEK。修「返回键退桌面后拔卡，再点图标可无卡登录」，
     * 见 [RealUsbManager.revalidatePresence]。
     */
    fun revalidatePresence() {
        if (real.revalidatePresence()) scope.launch { real.closeDevice() }
    }

    // —— 后台无操作自动登出（需求文档「APP转入后台后5分钟无操作即退出登录」）——

    private var inactivityJob: Job? = null

    /**
     * 退到后台：挂自动登出计时（仅已认证时）。超时时长读 [SettingsStore]（默认 5 分钟，用户可改）。
     *
     * 计时器挂在**单例**上而不是 `DeviceViewModel`（`[usb]` 安全修复 2026-07-29）：后者是 Activity 作用域，
     * 按**返回键**会 finish Activity → `onCleared` → `viewModelScope` 连同计时器一起取消，而认证态/DEK
     * 都在本单例上随进程存活 → 退出后**永不登出**，回来还是登录态。搬到进程作用域后返回键与 Home 键
     * 行为一致：超时前回来则取消计时（[onAppForeground]），超时未回来则登出。
     */
    fun onAppBackground() {
        if (deviceStatus.value.status != UsbDeviceStatus.AUTHENTICATED) return
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(settingsStore.inactivityTimeoutMinutes.first() * 60_000L)
            logout()
        }
    }

    /** 回到前台：取消自动登出计时。新旧 Activity 面对的是同一个单例，故返回键重建后也能取消上次挂的计时。 */
    fun onAppForeground() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    /** 用当前驱动模式重连（登录页驱动模式面板切换后触发，测试鸿蒙登录慢用）。 */
    fun reconnectWithCurrentDriverMode() {
        scope.launch { real.reconnect() }
    }

    /** 真卡序列号（诊断 / 后续 P2P deviceSn）。 */
    fun realSerialNumber(): String? = real.getSerialNumber()

    /** 文件增删后刷新「已用空间」（重读 SFGetCapacity → deviceStatus）。 */
    fun refreshCapacity() = real.refreshCapacity()
}
