package com.example.midun.data

import com.example.midun.data.local.LocalAuthManager
import com.example.midun.data.model.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 账户/会话门面（无卡版）。把 [AuthOps] 业务方法 / 设备状态统一转发到纯软件后端 [LocalAuthManager]，
 * 并在 wipe 成功后清共享聊天/日志仓库（本地文件清在 [LocalAuthManager]）。
 *
 * **无插拔**：无卡设备恒在，本类在构造时 [LocalAuthManager.connect] 一次，把状态从 DISCONNECTED 直接置
 * CONNECTED（按本地 `.auth` 是否存在判已初始化）→ Splash 据此路由到登录 / 初始化，无需 USB 事件。
 *
 * 历史：真卡版路由到 `RealUsbManager`（FSShell 开盘=认证）；bobo-nocard 收成纯软件单路径。
 */
@Singleton
class AccountManager @Inject constructor(
    private val local: LocalAuthManager,
    // wipe 成功后清共享聊天/日志仓库（清本地侧车 / 内存明文）。
    private val chatRepo: ChatRepository,
    private val operationLog: OperationLogRepository
) : AuthOps {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val deviceStatus: StateFlow<DeviceInfo> = local.deviceStatus

    init {
        // 启动即连接：设备恒在，把状态置 CONNECTED + 探测是否已初始化（不依赖 USB 插拔广播）。
        scope.launch { local.connect() }
    }

    // —— AuthOps 路由（全转发本地后端）——
    override suspend fun initDevice(phone: String, password: String, bindDevice: Boolean) =
        local.initDevice(phone, password, bindDevice)

    override suspend fun authenticate(phone: String, password: String) = local.authenticate(phone, password)
    override fun verifyPassword(password: String) = local.verifyPassword(password)
    override fun logout() = local.logout()

    /**
     * 恢复出厂 / 一键清理：[LocalAuthManager] 清本地用户文件（wipeUserData）或整体清除 + 销毁 KEK（wipeAll），
     * 此处补清共享聊天/日志仓库。
     */
    override suspend fun wipeAll() = local.wipeAll().also { if (it.isSuccess) clearRepos() }
    override suspend fun wipeUserData() =
        local.wipeUserData().also { if (it.isSuccess) { clearRepos(); local.refreshCapacity() } }

    private fun clearRepos() {
        chatRepo.clear()
        operationLog.clear()
    }

    override suspend fun updateBinding(bind: Boolean): Result<Unit> = local.updateBinding(bind)
    override suspend fun updateKey() = local.updateKey()

    override suspend fun getExitClearPrefs(): ExitClearPrefs = local.getExitClearPrefs()
    override suspend fun setExitClearPrefs(prefs: ExitClearPrefs): Result<Unit> = local.setExitClearPrefs(prefs)

    // —— 兼容旧插拔入口（DeviceViewModel/MainActivity 仍调用，NC4 随广播一并移除）——
    fun onUsbAttached() {
        scope.launch { local.connect() }
    }

    fun onUsbDetached() {
        // 无卡无物理拔出；敏感内存清理由各仓库监听 deviceStatus 离开 AUTHENTICATED 自动处理。
    }

    /** 本机稳定设备 id（P2P deviceSn，替代真卡 SN）。 */
    fun realSerialNumber(): String? = local.deviceId()

    /** 文件增删后刷新「已用空间」（重读手机存储容量 → deviceStatus）。 */
    fun refreshCapacity() = local.refreshCapacity()
}
