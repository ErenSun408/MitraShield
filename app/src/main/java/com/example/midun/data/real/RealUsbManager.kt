package com.example.midun.data.real

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.midun.data.ExitClearPrefs
import com.example.midun.data.UsbCardOps
import com.example.midun.data.crypto.CardKeystore
import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.model.UsbDeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import seczure.device.usb.USBStorageHelper
import seczure.fsudisk.fsshell.FSShellInstance
import seczure.fsudisk.fsshell.LibJniFSShell

/**
 * 真实 FSShell 安全卡管理器（M11.3）。实现 [UsbCardOps]，由 [com.example.midun.data.SecurityCardManager]
 * 在「真卡」模式下路由到此。
 *
 * **两层打开**：
 * - **USB 层** [connectUsb]：`USBStorageHelper` 申请权限 + 拿设备句柄，拼「外部设备」diskName。不验密码。
 * - **FSShell 盘层** [authenticate]/[initDevice]：`SFOpenDiskEx(diskName, pwd)` 用密码打开隐藏区盘。
 *   **「打开盘」= 「密码认证」是同一调用**（区别于 Mock 的 simulateInsert/authenticate 分离）。
 *
 * **密码**：App 层 `sha256(明文)` 当作密码传卡（满足需求「SHA256 后传卡」、不明文）。卡出厂默认密码为
 * 明文 `123456`；[initDevice] 把它改成 `sha256(用户密码)`，之后 [authenticate] 都用哈希。
 *
 * **R8**：`LibJniFSShell`/`USBStorageHelper` 含 JNI native 方法，release 混淆必须 keep（M11.7）。
 * **真机依赖**：USB 权限、`SFOpenDiskEx` 打开均需真卡 + OTG 真机联调；本地只保证编译 + 逻辑正确。
 */
@Singleton
class RealUsbManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val realFileSystem: RealFileSystem,
    // App 层文件密钥库（M12.1）：init 生成落卡、auth 解锁载入 DEK、登出/拔卡/恢复出厂锁定清零。
    private val cardKeystore: CardKeystore
) : UsbCardOps {

    // 懒加载：getLibFSShellInstance() 会 System.loadLibrary ~15MB native 库。@Singleton 在启动构建依赖图时
    // 就会造出本类——若饿汉初始化,这次 loadLibrary 落在主线程 onCreate 上 → 拉长启动白屏。改 by lazy 推迟到
    // 首次真正用卡(connectUsb 在 IO 线程)才加载,把它移出启动主线程路径。
    private val fsShell: LibJniFSShell by lazy { FSShellInstance.getLibFSShellInstance() }
    private var usbHelper: USBStorageHelper? = null
    /** USB Open 成功后拼好的「外部设备」diskName，供 SFOpenDiskEx 复用。 */
    private var diskName: String? = null

    /** 本次会话登录密码的 sha256（认证/初始化成功时记，登出/拔卡清）。供 [verifyPassword] 不重开盘校验。 */
    private var sessionPwdHash: String? = null

    /**
     * 连接单飞标志（登录慢/闪退/反复密码错误的公共病根修复）：[connectUsb] 入口 CAS 置位、退出清零。
     * 鸿蒙等机型会重复/杂散地发 `ACTION_USB_DEVICE_ATTACHED` 广播，原实现每次都并发重跑 [connectUsb] →
     * 覆盖已认证会话、并发踩踏 native 单例。置位期间到达的重复 attach 直接忽略。
     */
    private val connecting = AtomicBoolean(false)

    /**
     * 当前已打开设备的系统路径（`UsbDevice.deviceName`，如 `/dev/bus/usb/001/005`）。
     * 用于识别「换了一个设备」——[connectUsb] 的去重守卫只能据此判断 attach 是杂散重播还是真的重新插卡。
     */
    private var openedDeviceName: String? = null

    /**
     * 拔卡代次：每次 [closeDevice] +1。[connectUsb] 入口取快照、发布 CONNECTED 前比对——
     * 快速拔插时 attach/detach 是两条并发协程（`SecurityCardManager` 各 launch 一次、无序），
     * 若 connect 跑完才轮到 detach 收尾，会把刚建好的连接清掉并卡在 DISCONNECTED（须重启 App）。
     */
    private val detachEpoch = AtomicInteger(0)

    private val _deviceStatus = MutableStateFlow(DeviceInfo())
    override val deviceStatus: StateFlow<DeviceInfo> = _deviceStatus.asStateFlow()

    /**
     * USB 层打开（对应「插卡」）：枚举 → 申请权限并 `Open` 拿句柄 → 拼 diskName → 探测是否已初始化。
     * 成功后状态 → CONNECTED（尚未认证）。**不打开盘、不验密码**。
     *
     * 「是否已初始化」探测：试 `SFOpenDiskEx(默认密码 123456)`——能开=仍是默认密码=**未初始化**（走
     * 初始化向导）；开不了=密码已改=**已初始化**（走登录）。探测后立即关盘。
     * ⚠️ 若卡有硬件失败次数锁定，已初始化卡的这次探测会消耗一次尝试——真机需确认。
     */
    suspend fun connectUsb(device: UsbDevice? = null): Result<Unit> = withContext(Dispatchers.IO) {
        // 单飞去重：已有一次连接流程在跑 → 忽略这次杂散/重复 attach（鸿蒙常连发多条广播）。
        if (!connecting.compareAndSet(false, true)) return@withContext Result.success(Unit)
        val epoch = detachEpoch.get() // 本次连接的拔卡代次快照，发布 CONNECTED 前比对
        try {
            // 已连接/已认证时的重复 attach 直接忽略：绝不重跑探测把 AUTHENTICATED 覆盖成 CONNECTED 且盘不关
            //（那正是「登录后被踢回登录页、之后反复报密码错误、须重启 App」的根因）。
            //
            // 但只在**同一个设备**上忽略。守卫信的是内存状态，而内存状态会过期：App 被系统冻结/Activity 已
            // 销毁（receiver 随之注销）时拔卡，DETACHED 收不到 → usbHelper 与 diskName 双双失效却还留着。
            // 此时重新插卡若也照样早退，登录就会拿失效的 diskName 调 SFOpenDiskEx 而被报成「密码错误」，
            // 只有杀掉 App（连同单例）才能恢复。设备路径不同 = 真的重新插过卡 → 必须拆旧句柄重开。
            val cur = _deviceStatus.value.status
            val sameDevice = device == null || openedDeviceName == null ||
                device.deviceName == openedDeviceName
            if (usbHelper != null && sameDevice &&
                (cur == UsbDeviceStatus.CONNECTED || cur == UsbDeviceStatus.AUTHENTICATED)) {
                return@withContext Result.success(Unit)
            }
            releaseUsbLayer() // 换设备/重连：先拆掉可能已失效的旧句柄，避免 claimInterface 与旧 fd 打架
            val helper = USBStorageHelper(context, context.packageName).also { usbHelper = it }
            helper.PermissionHandler = Handler(Looper.getMainLooper(), Handler.Callback { true })
            // 双保险信号源（客户机型 SDK 私有权限标志时序对不上）：用 Android 系统 UsbManager.hasPermission
            // 作可靠的授权真信号，[awaitPermissionAndReopen] 优先据此重开，不再只依赖 SDK 标志。
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

            val list = helper.GetList()
            if (list.count == 0) {
                _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
                return@withContext Result.failure(IllegalStateException("未找到 USB 设备"))
            }
            val name = list.GetName(0)
            // SDK 怪癖（javap 核实 RequestPermission）：未授权时首次 Open 会异步弹授权框、但**立即返回 false**
            // （返回的是请求前的旧 hasPermission）。故首次失败后等授权结果：授权了再 Open 一次即成功（此时
            // hasPermission 已被系统缓存为 true）；拒绝/超时/真·打开失败 → 保持 DISCONNECTED（白屏等待、不退出）。
            if (!helper.Open(name) && !awaitPermissionAndReopen(helper, name, device, usbManager)) {
                _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
                return@withContext Result.failure(
                    IllegalStateException("打开 USB 失败（未授权或被拒绝）")
                )
            }
            // 授权通过、设备已打开 → 进入「检测中」：UI 据此显 Splash 检测页，再跑下面的 native 探测。
            _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.CONNECTING)

            val dn = buildExternalDiskName(helper, name).also { diskName = it }
            openedDeviceName = device?.deviceName

            // SFDiskGetSN 需盘已打开才能读。先试免密直读（部分卡可经 USB 句柄读硬件序列号）；读不到则在
            // 默认密码探测打开盘时顺带读（盘临时打开）。默认密码能开 = 未初始化。已初始化卡此处读不到 SN
            // （需密码开盘）→ 登录后由 authenticate 补读。
            var sn = runCatching { synchronized(fsShell) { fsShell.SFDiskGetSN(dn) } }.getOrNull().orEmpty()
            val canOpenDefault = synchronized(fsShell) { fsShell.SFOpenDiskEx(dn, DEFAULT_PASSWORD) } == 0
            if (canOpenDefault) {
                if (sn.isEmpty()) sn = readSn()
                runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
            }
            // 连接期间发生过拔卡（并发的 closeDevice 已把状态清成 DISCONNECTED）→ 别把刚探测的结果
            // 盖回 CONNECTED：那会留下一个「显示已连接、句柄却已失效」的状态，同样只能重启 App。
            if (detachEpoch.get() != epoch) {
                releaseUsbLayer()
                _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
                return@withContext Result.failure(IllegalStateException("连接期间安全卡被拔出"))
            }
            _deviceStatus.value = DeviceInfo(
                isInitialized = !canOpenDefault,
                deviceId = sn,
                status = UsbDeviceStatus.CONNECTED
            )
            Result.success(Unit)
        } catch (e: Exception) {
            _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
            Result.failure(e)
        } finally {
            connecting.set(false)
        }
    }

    /**
     * 首次 [USBStorageHelper.Open] 失败后等待 USB 授权结果并重试一次（SDK 怪癖见 [connectUsb]）。
     *
     * **双保险（客户机型：SDK 私有权限标志时序对不上 → 首插授权后不进、须重启，`f5eec8e` 修复的遗留风险）**：
     * 主信号改用 Android 系统的 [UsbManager.hasPermission]（设备无关、始终反映真实授权态），不再只依赖 SDK 的
     * `FReceivePermission/FGrantedPermission`——那两个标志在部分机型不按预期置位，导致原实现直接判「真失败」退出。
     * 一旦系统或 SDK 任一确认已授权即再 `Open` 一次；系统+SDK 均确认被拒 → 立即 false；否则轮询到超时。
     */
    private suspend fun awaitPermissionAndReopen(
        helper: USBStorageHelper, name: String, device: UsbDevice?, usbManager: UsbManager?
    ): Boolean {
        // 系统级授权真信号（可查时优先）。
        fun sysGranted(): Boolean = device != null && usbManager != null && usbManager.hasPermission(device)
        // 已授权（系统或 SDK 任一确认）。
        fun granted(): Boolean = sysGranted() || (helper.FReceivePermission && helper.FGrantedPermission)
        // 明确被拒：SDK 收到结果且未授权，且系统也确认无权限。
        fun denied(): Boolean = helper.FReceivePermission && !helper.FGrantedPermission && !sysGranted()

        if (granted()) return helper.Open(name)
        // 无 device 可观察（理论仅旧 mock 路径）时退回原 SDK-标志判据，避免真失败空等满超时。
        if (device == null && !helper.FRequestingPermission && !helper.FReceivePermission) return false

        var waited = 0
        while (waited < PERMISSION_TIMEOUT_MS) {
            if (granted()) return helper.Open(name)
            if (denied()) return false
            delay(PERMISSION_POLL_MS.toLong()); waited += PERMISSION_POLL_MS
        }
        return false
    }

    /** 读真卡 SN（`SFDiskGetSN` 需盘已打开；打开后 driveName 参数被忽略，传 ""）。读不到回空串。 */
    private fun readSn(): String = runCatching { synchronized(fsShell) { fsShell.SFDiskGetSN("") } }.getOrNull().orEmpty()

    /**
     * 初始化：用默认密码打开 → `SFDiskSetPassword(sha256(新密码))` 改密码 → 读 SN/写绑定 → **关盘**，
     * 状态退回 CONNECTED（已初始化）。
     *
     * **为何关盘**：改完密码后盘仍以「默认密码会话」处于打开态。产品流程是初始化完 → 登录页用新密码登录，
     * 而登录 [authenticate] 走 `SFOpenDiskEx` 会对「已打开的盘」重复开盘 → 失败 →「刚设的密码报密码错误」
     * （真机实测，须切模拟再切回真卡才好——切换会 [closeDevice] 关盘）。此处主动关盘，登录即可干净开盘。
     */
    override suspend fun initDevice(password: String, bindDevice: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dn = diskName ?: return@withContext Result.failure(IllegalStateException("USB 未连接"))
            if (synchronized(fsShell) { fsShell.SFOpenDiskEx(dn, DEFAULT_PASSWORD) } != 0) {
                return@withContext Result.failure(IllegalStateException("默认密码打开失败（卡可能已初始化）"))
            }
            val ret = synchronized(fsShell) { fsShell.SFDiskSetPassword(sha256(password)) }
            if (ret != 0) {
                runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
                return@withContext Result.failure(IllegalStateException("设置密码失败，错误码=$ret"))
            }
            // 绑定（M11.6.6）：选中绑定则写卡内 0:/.bind = 本机 androidId（需盘已打开）。写卡失败 → 视为未绑定
            // （boundId=null），不谎报已绑定；初始化主流程（密码+keystore）已成，不因可选绑定写失败而整体失败。
            val boundId = if (bindDevice) androidId().takeIf { writeBoundId(it) } else null
            // App 层密钥库（M12.1）：生成全新 DEK/KEK，原始落卡 0:/.midun_keystore（盘已打开、关盘前）。
            // DEK 同时入内存，但本次 init 完会关盘+清会话 → 登录时再由 authenticate 载入。
            // M12.4：落盘失败必须让 init 失败——否则会留下「已初始化却无 keystore」的卡 → 后续静默明文。
            if (!realFileSystem.saveKeystoreRaw(cardKeystore.createNew())) {
                runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
                cardKeystore.lock()
                return@withContext Result.failure(IllegalStateException("密钥库写入失败，请恢复出厂后重试"))
            }
            val sn = readSn() // 盘已打开，补读真实 SN
            // 关盘，回到「已初始化、未认证」态 → 登录页用新密码 SFOpenDiskEx 重新干净开盘。
            runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
            cardKeystore.lock() // init 后即关盘，DEK 不应留存——登录时重新载入
            sessionPwdHash = null
            _deviceStatus.value = _deviceStatus.value.copy(
                isInitialized = true,
                status = UsbDeviceStatus.CONNECTED,
                deviceId = sn.ifEmpty { _deviceStatus.value.deviceId },
                boundPhoneId = boundId
            )
            Result.success(Unit)
        }

    /**
     * 认证（登录）：`SFOpenDiskEx(diskName, sha256(password))`，打开盘即认证成功。
     * **绑定强制校验（M11.6.6）**：开盘后读卡内 `0:/.bind`，若存在且 != 本机 androidId → 拒登并关盘
     * （换手机/重装会被锁——安全设计，解绑需在原绑定机上做或用 PC 串口工具）。无 `.bind` = 未绑定，放行。
     */
    override suspend fun authenticate(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dn = diskName ?: return@withContext Result.failure(IllegalStateException("USB 未连接"))
        val ret = synchronized(fsShell) { fsShell.SFOpenDiskEx(dn, sha256(password)) }
        if (ret != 0) {
            // 开盘失败有两种原因，报错不能一律说成密码错误：真的密码不对，或句柄已失效（卡被拔走/换了设备）。
            // 用 SDK 重新枚举区分——枚举不到设备 = 卡不在了，退回 DISCONNECTED 让 UI 显拔卡遮罩，
            // 而不是让用户对着「密码错误」反复重试一张根本不在的卡。枚举只走系统 UsbManager，不消耗卡的尝试次数。
            val stillPresent = runCatching { usbHelper?.GetList()?.count ?: 0 }.getOrDefault(0) > 0
            if (!stillPresent) {
                closeDevice()
                return@withContext Result.failure(IllegalStateException("安全卡已断开，请重新插入后再试"))
            }
            return@withContext Result.failure(IllegalStateException("密码错误或打开失败，错误码=$ret"))
        }
        val boundId = readBoundId()
        if (boundId != null && boundId != androidId()) {
            runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
            return@withContext Result.failure(IllegalStateException("此卡已绑定其他设备，无法在本机登录"))
        }
        // App 层密钥库（M12.1）：开盘后载入 keystore、解出 DEK 驻内存。
        // M12.4：keystore 缺失/损坏 → 拒登并关盘，**不静默跑明文**。正常卡（恢复出厂 + 重新 init）必有 keystore；
        // 走到这里 = 异常卡（旧卡未重置 / keystore 损坏），提示恢复出厂重新初始化。
        if (!cardKeystore.load(realFileSystem.loadKeystoreRaw())) {
            runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
            cardKeystore.lock()
            return@withContext Result.failure(IllegalStateException("密钥库缺失或损坏，请恢复出厂后重新初始化"))
        }
        sessionPwdHash = sha256(password)
        // 退出自动清理「下次登录补清」：盘已打开、AUTHENTICATED 尚未置位（ChatRepository 未加载）→ 无竞态。
        // 开启则在进主界面前清空对应数据；开关文件是根级侧车，clear() 不会删它 → 每次登录都清。
        val exitClear = readExitClearPrefs()
        if (exitClear.clearContacts) runCatching { realFileSystem.deleteSidecar(CHAT_SIDECAR) }
        if (exitClear.clearFiles) realFileSystem.clear()
        // 任一「退出时清空」开启，一并清首页最近操作日志（删在置 AUTHENTICATED 之前，
        // OperationLogRepository 随后 store.load() 读到空、_logs 维持登出时清空的 emptyList，无竞态）。
        if (exitClear.clearContacts || exitClear.clearFiles) runCatching { realFileSystem.deleteSidecar(OPLOG_SIDECAR) }
        val sn = readSn() // 盘已打开，补读真实 SN（已初始化卡在 connectUsb 阶段读不到）
        val (total, free) = readCapacity()
        _deviceStatus.value = _deviceStatus.value.copy(
            status = UsbDeviceStatus.AUTHENTICATED,
            deviceId = sn.ifEmpty { _deviceStatus.value.deviceId },
            totalBytes = total,
            freeBytes = free,
            boundPhoneId = boundId
        )
        Result.success(Unit)
    }

    /** 不重开盘校验密码（登录后敏感操作二次确认）：比对会话 sha256(登录密码)。 */
    override fun verifyPassword(password: String): Boolean =
        sessionPwdHash != null && sha256(password) == sessionPwdHash

    /** 读隐藏区容量（M11.6.1）：`SFGetCapacity(root, long[2])` → [总字节, 空闲字节]；需盘已打开。失败回 0,0。 */
    private fun readCapacity(): Pair<Long, Long> {
        val out = LongArray(2)
        return if (synchronized(fsShell) { fsShell.SFGetCapacity("0:/", out) } == 0) out[0] to out[1] else 0L to 0L
    }

    /**
     * 重读容量并刷新 deviceStatus 的「已用空间」（导入/删除文件后调用）。容量在登录时读一次后不会自动变，
     * 故文件增删后须主动刷新，否则首页/设置显示的是登录那一刻的快照。仅在已认证（盘打开）时有效。
     */
    fun refreshCapacity() {
        if (_deviceStatus.value.status != UsbDeviceStatus.AUTHENTICATED) return
        val (total, free) = readCapacity()
        if (total > 0) {
            _deviceStatus.value = _deviceStatus.value.copy(totalBytes = total, freeBytes = free)
        }
    }

    /** 退出登录：关盘但保留 USB 句柄，状态退回 CONNECTED。 */
    override fun logout() {
        runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
        sessionPwdHash = null
        cardKeystore.lock() // 锁定后清内存 DEK（M12.1）
        _deviceStatus.value = _deviceStatus.value.copy(status = UsbDeviceStatus.CONNECTED)
    }

    /** 拔卡 / 完全释放：关盘 + 关 USB 句柄，状态 DISCONNECTED。 */
    suspend fun closeDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        detachEpoch.incrementAndGet() // 让并发中的 connectUsb 知道「这次连接已被拔卡作废」
        releaseUsbLayer()
        cardKeystore.lock() // 拔卡清内存 DEK（M12.1）
        _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
        Result.success(Unit)
    }

    /**
     * 释放 USB/盘层句柄（关盘 + 关 helper + 清路径/会话密码），**不动状态、不计拔卡代次**。
     * 供 [closeDevice] 与 [connectUsb] 的重连路径共用——后者只是换句柄，不该被自己的代次判为拔卡。
     */
    private fun releaseUsbLayer() {
        runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
        runCatching { usbHelper?.Close() }
        usbHelper = null
        diskName = null
        openedDeviceName = null
        sessionPwdHash = null
    }

    // —— 以下依赖后续子阶段，先占位 ——

    /**
     * 恢复出厂（M11.6.7，SDK 补全 `SFFormat` 后恢复真擦）。厂商新 .so 已实现 `SFFormat` JNI 符号
     * （旧 .so 缺 `Java_..._SFFormat` 致 `UnsatisfiedLinkError` 崩进程，换库见 commit `1da206c`）。
     * 策略「真擦 + 兜底 + 回未初始化态」：
     *  1. `SFFormat("0:/")` 强制格式化隐藏区（比逐文件删更彻底、抗取证恢复）；
     *  2. 失败（`ret != 0`）则降级为逐文件 [RealFileSystem.clear] + 删侧车，保证数据至少被清空；
     *  3. `SFDiskSetPassword(123456)` 重置回出厂默认密码 → connectUsb 探测默认密码视为未初始化 → Init 向导。
     *
     * **A. 已登录（AUTHENTICATED）**：盘已打开 = `SFFormat` 合法用法。强擦，失败则降级逐文件清，再 `SFDiskSetPassword` 重置默认密码。
     * **B. 忘记密码（CONNECTED，盘未打开）**：**不调 `SFFormat`**——真机实测盘未开时调用会原生崩溃或返回 -1。诚实失败、指向 PC 串口管理工具。
     *
     * 真机待验：① `SFFormat` 返回码/是否需开盘；② 格式化后开盘句柄是否仍可 `SFDiskSetPassword`
     * （若 SFFormat 关盘，第 3 步可能失败——此时数据已擦，UI 报错但卡是干净的）。
     */
    override suspend fun wipeAll(): Result<Unit> = withContext(Dispatchers.IO) {
        // 忘记密码（未登录、盘未打开）不能调 SFFormat：真机实测「开过盘又登出」的状态下调用会原生崩溃
        // （SIGSEGV，runCatching 抓不住 .so 的崩溃），「从没开盘」则返回 -1 → 一律诚实失败、指向 PC 工具。
        if (_deviceStatus.value.status != UsbDeviceStatus.AUTHENTICATED) {
            return@withContext Result.failure(
                IllegalStateException("真卡恢复出厂需先登录；忘记密码无法在 App 内重置，请用 PC 串口管理工具")
            )
        }
        // 已登录、盘已打开 = SFFormat 的合法用法：真擦 → 失败降级逐文件清 → 重置默认密码回未初始化态。
        runCatching {
            val fmt = synchronized(fsShell) { LibJniFSShell.SFFormat(ROOT) }
            if (fmt != 0) {
                realFileSystem.clear() // 删所有文件夹/文件/元数据
                // clear() 不含这些隐藏侧车，单独删（聊天 / 日志 含 .bak/.tmp 残留；绑定）。
                runCatching { realFileSystem.deleteSidecar(CHAT_SIDECAR) }
                runCatching { realFileSystem.deleteSidecar(OPLOG_SIDECAR) }
                runCatching { realFileSystem.deleteFile(BIND_PATH) }
            }
            // 回出厂默认密码（SFFormat 是否自动重置密码未知，显式兜底确保未初始化态）。
            val ret = synchronized(fsShell) { fsShell.SFDiskSetPassword(DEFAULT_PASSWORD) }
            if (ret != 0) throw IllegalStateException("重置密码失败，错误码=$ret")
            finishReset()
        }
    }

    /** 恢复出厂收尾：关盘、清会话密码、状态退回未初始化（CONNECTED）→ connectUsb 重探测走 Init 向导。 */
    private fun finishReset() {
        runCatching { synchronized(fsShell) { fsShell.SFCloseDisk() } }
        sessionPwdHash = null
        cardKeystore.lock() // 恢复出厂：keystore 已被格式化/清除，清内存 DEK（M12.1），下次 init 重生成
        _deviceStatus.value = DeviceInfo(
            isInitialized = false,
            status = UsbDeviceStatus.CONNECTED,
            deviceId = _deviceStatus.value.deviceId
        )
    }

    /**
     * 一键清理（M11.6.5）：保留密码/登录态，仅清隐藏区用户文件（[RealFileSystem.clear]：删所有文件夹/文件/
     * 元数据侧车）。聊天/日志由门面统一清（写穿空到卡）。保持 AUTHENTICATED。
     */
    override suspend fun wipeUserData(): Result<Unit> = realFileSystem.clear()

    /**
     * 切换绑定（M11.6.6）：bind=true 写卡内 `0:/.bind`=本机 androidId；false 删 `.bind` 解绑。需盘已打开。
     * **写卡失败如实返回 failure**——绝不在卡里没真正写/删的情况下谎报绑定状态（否则下次登录的绑定校验
     * 形同虚设）。仅在卡操作成功后才翻转内存 `boundPhoneId`。
     */
    override suspend fun updateBinding(bind: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (bind) {
            val id = androidId()
            if (!writeBoundId(id)) {
                return@withContext Result.failure(IllegalStateException("绑定写入失败，请重试"))
            }
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = id)
        } else {
            if (realFileSystem.deleteFile(BIND_PATH).isFailure) {
                return@withContext Result.failure(IllegalStateException("解绑失败，请重试"))
            }
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = null)
        }
        Result.success(Unit)
    }

    /** 写卡内绑定文件 `0:/.bind` = [id]（本机 androidId）。返回是否真正写入成功。 */
    private fun writeBoundId(id: String): Boolean =
        realFileSystem.writeFile(BIND_PATH, id.toByteArray(Charsets.UTF_8).inputStream()).isSuccess

    /** 读卡内绑定 androidId；无 `.bind`（未绑定）回 null。需盘已打开。 */
    private fun readBoundId(): String? {
        val out = ByteArrayOutputStream()
        return if (realFileSystem.readFile(BIND_PATH, out).isSuccess) {
            out.toString(Charsets.UTF_8.name()).trim().ifEmpty { null }
        } else null
    }

    /**
     * 密钥更新（M12.6）：**App 层 KEK 轮换**——重生成 KEK、用它重新包裹**不变的 DEK**，安全覆盖卡内 keystore。
     * DEK 不变 → 隐私文件夹已有文件无需重新加密、不会丢失。需已认证（DEK 在内存、盘已打开）。
     *
     * **诚实定位**：这是 App 层「文件封装密钥」的轮换，**不提升保密强度**——真正的保护是安全卡硬件加密 + 登录
     * 密码（《密钥管理》层 SDK 不开放轮换）。失败如实返回，不再吞 Result 假报成功。
     */
    override suspend fun updateKey(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_deviceStatus.value.status != UsbDeviceStatus.AUTHENTICATED) {
            return@withContext Result.failure(IllegalStateException("请先登录后再更新密钥"))
        }
        val blob = cardKeystore.rewrap()
            ?: return@withContext Result.failure(IllegalStateException("密钥库未解锁，无法更新密钥"))
        if (!realFileSystem.rewriteKeystoreRaw(blob)) {
            return@withContext Result.failure(IllegalStateException("密钥库写入失败，密钥未更新"))
        }
        Result.success(Unit)
    }

    // —— 退出自动清理偏好（卡内 `0:/.midun_exitclear.json`，下次登录补清）——

    override suspend fun getExitClearPrefs(): ExitClearPrefs = withContext(Dispatchers.IO) {
        readExitClearPrefs()
    }

    override suspend fun setExitClearPrefs(prefs: ExitClearPrefs): Result<Unit> = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("contacts", prefs.clearContacts)
            .put("files", prefs.clearFiles)
            .toString()
        if (realFileSystem.writeFile(EXITCLEAR_PATH, json.byteInputStream()).isFailure) {
            return@withContext Result.failure(IllegalStateException("设置写入失败，请重试"))
        }
        Result.success(Unit)
    }

    /** 读退出清理偏好：盘已打开即可读（不依赖 AUTHENTICATED 门，供 [authenticate] 补清时调用）。缺失/失败回全 false。 */
    private fun readExitClearPrefs(): ExitClearPrefs {
        val out = ByteArrayOutputStream()
        if (realFileSystem.readFile(EXITCLEAR_PATH, out).isFailure) return ExitClearPrefs()
        return runCatching {
            val o = JSONObject(out.toString(Charsets.UTF_8.name()))
            ExitClearPrefs(o.optBoolean("contacts", false), o.optBoolean("files", false))
        }.getOrDefault(ExitClearPrefs())
    }

    /** 真卡唯一序列号（接 P2P deviceSn 用，M11.6）。打开后 driveName 参数被忽略。 */
    fun getSerialNumber(): String? = runCatching { synchronized(fsShell) { fsShell.SFDiskGetSN("") } }.getOrNull()

    private fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    /** SHA-256(明文) → 小写 hex（密码传卡前哈希，需求要求）。 */
    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 拼「外部设备」diskName（SDK android_4.0 严格权限系统方式，文档 3.2 节）：
     * `name=fd;busnum=..;devaddr=..;fd=..;type=..;speed=..;ifaces=..;vid=..;pid=..;ep_in=..;ep_out=..;`
     */
    private fun buildExternalDiskName(h: USBStorageHelper, name: String): String =
        "$name=${h.FileHandle};" +
            "busnum=${h.BusNum};" +
            "devaddr=${h.DevAddr};" +
            "fd=${h.FileHandle};" +
            "type=${h.n_type};" +
            "speed=${h.n_speed};" +
            "ifaces=${h.nb_ifaces};" +
            "vid=${h.wVid};" +
            "pid=${h.wPid};" +
            "ep_in=${h.endpoint_in};" +
            "ep_out=${h.endpoint_out};"

    private companion object {
        const val DEFAULT_PASSWORD = "123456"
        /** 等 USB 授权结果的轮询间隔/超时（首次 Open 怪癖重试用）。120s 足够用户在弹框上操作。 */
        const val PERMISSION_POLL_MS = 200
        const val PERMISSION_TIMEOUT_MS = 120_000
        const val ROOT = "0:/"
        const val BIND_PATH = "0:/.bind"
        const val CHAT_SIDECAR = "0:/.midun_chat.json"
        const val OPLOG_SIDECAR = "0:/.midun_oplog.json"
        const val EXITCLEAR_PATH = "0:/.midun_exitclear.json"
    }
}
