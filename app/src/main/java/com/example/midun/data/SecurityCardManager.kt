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
import kotlinx.coroutines.channels.Channel
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

    // —— USB 插拔事件路由（来自 MainActivity / MiDunApp 广播，经 DeviceViewModel）——

    /** USB 生命周期事件，见 [usbEvents]。 */
    private sealed interface UsbEvent {
        data class Attached(val device: android.hardware.usb.UsbDevice?) : UsbEvent
        /** [generation] = 广播到达当场取的 [RealUsbManager.openGeneration]，用于识别过期投递。 */
        data class Detached(val deviceName: String?, val generation: Int) : UsbEvent
        /** 复核发现卡已不在 → 补做句柄/DEK 释放，见 [revalidatePresence]。 */
        object Release : UsbEvent
    }

    /**
     * USB 事件**串行队列**（单消费者，`[usb]` 2026-07-30）。
     *
     * 原先每个事件各起一条 `scope.launch`，彼此无序也无互斥（Dispatchers.IO 上真并行）：
     * - 快速拔插时 attach 与 detach 谁先跑纯看调度，detach 后跑就把刚建好的连接清成 DISCONNECTED——
     *   插着卡却显示无卡、5 秒退出遮罩，聊天会话也被 `P2PSessionManager` 的认证态监听一并拆掉。
     * - `MainActivity.onCreate` 里 [revalidatePresence] 的释放与紧随其后的 [onUsbAttached] 同样是两条协程，
     *   顺序反了就是「先连上、再关掉」。
     *
     * [RealUsbManager.lifecycleMutex] 只能保证「不并发」，保证不了「按到达顺序」，故这里再收一道：广播在主
     * 线程按序到达 → 按序入队 → 单协程按序消费。UNLIMITED + [Channel.trySend]：入队不阻塞主线程也不丢事件。
     */
    private val usbEvents = Channel<UsbEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            var pending: UsbEvent? = null // 合并时多取出来的那条非 attach 事件，留到下一轮按序处理
            while (true) {
                val event = pending ?: usbEvents.receive()
                pending = null
                when (event) {
                    is UsbEvent.Attached -> {
                        // 合并同一次插卡连发的 attach（鸿蒙一次插卡常发多条）：队列里立即可取的后续 attach
                        // 覆盖本条，只跑最后一条。原先这些重复广播由 `RealUsbManager.connecting` 的 CAS 丢弃
                        // （都落在第一条连接的飞行期内）；改成串行队列后若不合并，就会在第一条跑完后逐条重跑，
                        // 第一条失败收场时每次重跑都要再做一次默认密码探测 → 白烧卡的密码尝试次数。
                        var merged: UsbEvent.Attached = event
                        while (true) {
                            val next = usbEvents.tryReceive().getOrNull() ?: break
                            if (next is UsbEvent.Attached) merged = next else { pending = next; break }
                        }
                        real.connectUsb(merged.device)
                    }
                    is UsbEvent.Detached -> real.onDeviceDetached(event.deviceName, event.generation)
                    UsbEvent.Release -> real.closeDevice()
                }
            }
        }
    }

    fun onUsbAttached(device: android.hardware.usb.UsbDevice?) {
        usbEvents.trySend(UsbEvent.Attached(device))
    }

    /**
     * [detachedName] = 广播里被拔设备的 deviceName（可能为 null）；由 [RealUsbManager.onDeviceDetached] 判是否本卡。
     *
     * 开设备代次必须在**这里**取（= 广播到达当场，主线程），不能等到出队处理时再取：那时若已重新连上，
     * 就分不清「这条广播是过期投递」还是「刚连上的这台又被拔了」。
     */
    fun onUsbDetached(detachedName: String?) {
        usbEvents.trySend(UsbEvent.Detached(detachedName, real.openGeneration()))
    }

    /**
     * 复核插卡状态（Activity 每次启动时调）：内存说有卡但系统里已经没有 → 同步降级 DISCONNECTED，
     * 再异步释放句柄 / 清 DEK。修「返回键退桌面后拔卡，再点图标可无卡登录」，
     * 见 [RealUsbManager.revalidatePresence]。
     *
     * 降级同步、释放入队：队列保证这次释放一定排在同一次 `onCreate` 随后投递的 attach **之前**处理。
     */
    fun revalidatePresence() {
        if (real.revalidatePresence()) usbEvents.trySend(UsbEvent.Release)
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

    /** 真卡序列号（诊断 / 后续 P2P deviceSn）。 */
    fun realSerialNumber(): String? = real.getSerialNumber()

    /** 文件增删后刷新「已用空间」（重读 SFGetCapacity → deviceStatus）。 */
    fun refreshCapacity() = real.refreshCapacity()
}
