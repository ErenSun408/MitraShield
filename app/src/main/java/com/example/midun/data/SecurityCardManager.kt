package com.example.midun.data

import com.example.midun.data.mock.MockUsbManager
import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.real.RealUsbManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 安全卡门面（M11.3，facade 运行时切换）。持有 [MockUsbManager]（模拟）与 [RealUsbManager]（真卡）
 * 两套实现 + `useRealCard` 开关，上层（`AuthViewModel`/`DeviceViewModel`）统一注入本类，按开关把
 * 业务方法 / 设备状态 / USB 插拔事件路由到对应实现。
 *
 * 默认 **模拟模式**（`useRealCard=false`）——保留全部 mock 开发流（模拟器、无卡也能跑 UI）；真卡是
 * 增量叠加，由 DevControlPanel 的「模拟/真卡」开关切换，切到真卡时立即尝试 [RealUsbManager.connectUsb]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SecurityCardManager @Inject constructor(
    /** 暴露给 DevControlPanel 调 DEV 专属 simulate*（真卡无对应概念）。 */
    val mock: MockUsbManager,
    private val real: RealUsbManager
) : UsbCardOps {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _useRealCard = MutableStateFlow(false)
    val useRealCard: StateFlow<Boolean> = _useRealCard.asStateFlow()

    private fun active(): UsbCardOps = if (_useRealCard.value) real else mock

    /** 设备状态随开关切换到对应实现的状态流。 */
    override val deviceStatus: StateFlow<DeviceInfo> =
        _useRealCard
            .flatMapLatest { useReal -> if (useReal) real.deviceStatus else mock.deviceStatus }
            .stateIn(scope, SharingStarted.Eagerly, DeviceInfo())

    // —— UsbCardOps 路由 ——
    override suspend fun initDevice(password: String, bindDevice: Boolean) =
        active().initDevice(password, bindDevice)

    override suspend fun authenticate(password: String) = active().authenticate(password)
    override fun logout() = active().logout()
    override suspend fun wipeAll() = active().wipeAll()
    override suspend fun wipeUserData() = active().wipeUserData()
    override fun updateBinding(bind: Boolean) = active().updateBinding(bind)
    override suspend fun updateKey() = active().updateKey()

    // —— 模式切换 ——
    /**
     * 切换真卡/模拟。切到真卡：尝试连接已插入的卡（无新插入广播时也能连）；切回模拟：释放真卡。
     */
    fun setUseRealCard(useReal: Boolean) {
        if (_useRealCard.value == useReal) return
        _useRealCard.value = useReal
        if (useReal) scope.launch { real.connectUsb() }
        else scope.launch { real.closeDevice() }
    }

    // —— USB 插拔事件路由（来自 MainActivity 广播，经 DeviceViewModel）——
    fun onUsbAttached() {
        if (_useRealCard.value) scope.launch { real.connectUsb() } else mock.simulateInsert()
    }

    fun onUsbDetached() {
        if (_useRealCard.value) scope.launch { real.closeDevice() } else mock.simulateRemove()
    }

    // —— DEV 专属（仅模拟模式有意义）——
    fun simulateInsert() = mock.simulateInsert()
    fun simulateRemove() = mock.simulateRemove()
    fun simulateFirstInsert() = mock.simulateFirstInsert()

    /** 真卡序列号（真卡模式下供诊断/后续 P2P deviceSn）。 */
    fun realSerialNumber(): String? = real.getSerialNumber()
}
