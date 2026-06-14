package com.example.midun.data.real

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.midun.data.UsbCardOps
import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.model.UsbDeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    private val realFileSystem: RealFileSystem
) : UsbCardOps {

    private val fsShell: LibJniFSShell = FSShellInstance.getLibFSShellInstance()
    private var usbHelper: USBStorageHelper? = null
    /** USB Open 成功后拼好的「外部设备」diskName，供 SFOpenDiskEx 复用。 */
    private var diskName: String? = null

    /** 本次会话登录密码的 sha256（认证/初始化成功时记，登出/拔卡清）。供 [verifyPassword] 不重开盘校验。 */
    private var sessionPwdHash: String? = null

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
    suspend fun connectUsb(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val helper = USBStorageHelper(context, context.packageName).also { usbHelper = it }
            helper.PermissionHandler = Handler(Looper.getMainLooper(), Handler.Callback { true })

            val list = helper.GetList()
            if (list.count == 0) {
                _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
                return@withContext Result.failure(IllegalStateException("未找到 USB 设备"))
            }
            val name = list.GetName(0)
            if (!helper.Open(name)) {
                _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
                return@withContext Result.failure(
                    IllegalStateException("打开 USB 失败（可能未授权，请在系统弹框中允许后重试）")
                )
            }
            val dn = buildExternalDiskName(helper, name).also { diskName = it }

            // SFDiskGetSN 需盘已打开才能读。先试免密直读（部分卡可经 USB 句柄读硬件序列号）；读不到则在
            // 默认密码探测打开盘时顺带读（盘临时打开）。默认密码能开 = 未初始化。已初始化卡此处读不到 SN
            // （需密码开盘）→ 登录后由 authenticate 补读。
            var sn = runCatching { fsShell.SFDiskGetSN(dn) }.getOrNull().orEmpty()
            val canOpenDefault = fsShell.SFOpenDiskEx(dn, DEFAULT_PASSWORD) == 0
            if (canOpenDefault) {
                if (sn.isEmpty()) sn = readSn()
                runCatching { fsShell.SFCloseDisk() }
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
        }
    }

    /** 读真卡 SN（`SFDiskGetSN` 需盘已打开；打开后 driveName 参数被忽略，传 ""）。读不到回空串。 */
    private fun readSn(): String = runCatching { fsShell.SFDiskGetSN("") }.getOrNull().orEmpty()

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
            if (fsShell.SFOpenDiskEx(dn, DEFAULT_PASSWORD) != 0) {
                return@withContext Result.failure(IllegalStateException("默认密码打开失败（卡可能已初始化）"))
            }
            val ret = fsShell.SFDiskSetPassword(sha256(password))
            if (ret != 0) {
                runCatching { fsShell.SFCloseDisk() }
                return@withContext Result.failure(IllegalStateException("设置密码失败，错误码=$ret"))
            }
            // 绑定（M11.6.6）：选中绑定则写卡内 0:/.bind = 本机 androidId（需盘已打开）。
            val boundId = if (bindDevice) androidId().also { writeBoundId(it) } else null
            val sn = readSn() // 盘已打开，补读真实 SN
            // 关盘，回到「已初始化、未认证」态 → 登录页用新密码 SFOpenDiskEx 重新干净开盘。
            runCatching { fsShell.SFCloseDisk() }
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
        val ret = fsShell.SFOpenDiskEx(dn, sha256(password))
        if (ret != 0) {
            return@withContext Result.failure(IllegalStateException("密码错误或打开失败，错误码=$ret"))
        }
        val boundId = readBoundId()
        if (boundId != null && boundId != androidId()) {
            runCatching { fsShell.SFCloseDisk() }
            return@withContext Result.failure(IllegalStateException("此卡已绑定其他设备，无法在本机登录"))
        }
        sessionPwdHash = sha256(password)
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
        return if (fsShell.SFGetCapacity("0:/", out) == 0) out[0] to out[1] else 0L to 0L
    }

    /** 退出登录：关盘但保留 USB 句柄，状态退回 CONNECTED。 */
    override fun logout() {
        runCatching { fsShell.SFCloseDisk() }
        sessionPwdHash = null
        _deviceStatus.value = _deviceStatus.value.copy(status = UsbDeviceStatus.CONNECTED)
    }

    /** 拔卡 / 完全释放：关盘 + 关 USB 句柄，状态 DISCONNECTED。 */
    suspend fun closeDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { fsShell.SFCloseDisk() }
        runCatching { usbHelper?.Close() }
        usbHelper = null
        diskName = null
        sessionPwdHash = null
        _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
        Result.success(Unit)
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
                // clear() 不含这些隐藏侧车，单独删（聊天 / 日志 / 绑定）。
                listOf(CHAT_SIDECAR, OPLOG_SIDECAR, BIND_PATH).forEach { runCatching { realFileSystem.deleteFile(it) } }
            }
            // 回出厂默认密码（SFFormat 是否自动重置密码未知，显式兜底确保未初始化态）。
            val ret = fsShell.SFDiskSetPassword(DEFAULT_PASSWORD)
            if (ret != 0) throw IllegalStateException("重置密码失败，错误码=$ret")
            finishReset()
        }
    }

    /** 恢复出厂收尾：关盘、清会话密码、状态退回未初始化（CONNECTED）→ connectUsb 重探测走 Init 向导。 */
    private fun finishReset() {
        runCatching { fsShell.SFCloseDisk() }
        sessionPwdHash = null
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

    /** 切换绑定（M11.6.6）：bind=true 写卡内 `0:/.bind`=本机 androidId；false 删 `.bind` 解绑。需盘已打开。 */
    override suspend fun updateBinding(bind: Boolean): Unit = withContext(Dispatchers.IO) {
        if (bind) {
            val id = androidId()
            writeBoundId(id)
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = id)
        } else {
            realFileSystem.deleteFile(BIND_PATH)
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = null)
        }
    }

    /** 写卡内绑定文件 `0:/.bind` = [id]（本机 androidId）。 */
    private fun writeBoundId(id: String) {
        realFileSystem.writeFile(BIND_PATH, id.toByteArray(Charsets.UTF_8).inputStream())
    }

    /** 读卡内绑定 androidId；无 `.bind`（未绑定）回 null。需盘已打开。 */
    private fun readBoundId(): String? {
        val out = ByteArrayOutputStream()
        return if (realFileSystem.readFile(BIND_PATH, out).isSuccess) {
            out.toString(Charsets.UTF_8.name()).trim().ifEmpty { null }
        } else null
    }

    /** 密钥更新：FSShell **无 App 可调的密钥轮换接口**（《密钥管理》只有改密码）→ 诚实降级，不做。 */
    override suspend fun updateKey(): Result<Unit> =
        Result.failure(NotImplementedError("SDK 无密钥轮换接口（安全层不对普通开发者开放）"))

    /** 真卡唯一序列号（接 P2P deviceSn 用，M11.6）。打开后 driveName 参数被忽略。 */
    fun getSerialNumber(): String? = runCatching { fsShell.SFDiskGetSN("") }.getOrNull()

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
        const val ROOT = "0:/"
        const val BIND_PATH = "0:/.bind"
        const val CHAT_SIDECAR = "0:/.midun_chat.json"
        const val OPLOG_SIDECAR = "0:/.midun_oplog.json"
    }
}
