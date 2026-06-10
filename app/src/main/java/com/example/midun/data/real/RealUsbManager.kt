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
            diskName = buildExternalDiskName(helper, name)

            val sn = runCatching { fsShell.SFDiskGetSN("") }.getOrNull() ?: ""
            val notInitialized = probeDefaultPassword() // 默认密码能开 = 未初始化
            _deviceStatus.value = DeviceInfo(
                isInitialized = !notInitialized,
                deviceId = sn,
                status = UsbDeviceStatus.CONNECTED
            )
            Result.success(Unit)
        } catch (e: Exception) {
            _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
            Result.failure(e)
        }
    }

    /** 探测：默认密码 123456 能否打开盘（探测后立即关盘）。true=能开（卡未初始化）。 */
    private fun probeDefaultPassword(): Boolean {
        val dn = diskName ?: return false
        return if (fsShell.SFOpenDiskEx(dn, DEFAULT_PASSWORD) == 0) {
            runCatching { fsShell.SFCloseDisk() }
            true
        } else false
    }

    /** 初始化：用默认密码打开 → `SFDiskSetPassword(sha256(新密码))` 改密码 → 状态 AUTHENTICATED（保持打开）。 */
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
            // 绑定（M11.6.6）：选中绑定则写卡内 0:/.bind = 本机 androidId。
            val boundId = if (bindDevice) androidId().also { writeBoundId(it) } else null
            val (total, free) = readCapacity()
            _deviceStatus.value = _deviceStatus.value.copy(
                isInitialized = true,
                status = UsbDeviceStatus.AUTHENTICATED,
                boundPhoneId = boundId,
                totalBytes = total,
                freeBytes = free
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
        val (total, free) = readCapacity()
        _deviceStatus.value = _deviceStatus.value.copy(
            status = UsbDeviceStatus.AUTHENTICATED,
            totalBytes = total,
            freeBytes = free,
            boundPhoneId = boundId
        )
        Result.success(Unit)
    }

    /** 读隐藏区容量（M11.6.1）：`SFGetCapacity(root, long[2])` → [总字节, 空闲字节]；需盘已打开。失败回 0,0。 */
    private fun readCapacity(): Pair<Long, Long> {
        val out = LongArray(2)
        return if (fsShell.SFGetCapacity("0:/", out) == 0) out[0] to out[1] else 0L to 0L
    }

    /** 退出登录：关盘但保留 USB 句柄，状态退回 CONNECTED。 */
    override fun logout() {
        runCatching { fsShell.SFCloseDisk() }
        _deviceStatus.value = _deviceStatus.value.copy(status = UsbDeviceStatus.CONNECTED)
    }

    /** 拔卡 / 完全释放：关盘 + 关 USB 句柄，状态 DISCONNECTED。 */
    suspend fun closeDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { fsShell.SFCloseDisk() }
        runCatching { usbHelper?.Close() }
        usbHelper = null
        diskName = null
        _deviceStatus.value = DeviceInfo(status = UsbDeviceStatus.DISCONNECTED)
        Result.success(Unit)
    }

    // —— 以下依赖后续子阶段，先占位 ——

    /**
     * 恢复出厂 / 忘记密码（M11.6.5，用户定「试 SFFormat 强擦」）：`SFFormat("0:/")` 强制格式化隐藏区，
     * 抹掉所有数据（文件/聊天/日志侧车/绑定）。**未知项（须真机验）**：SFFormat 是否需先打开盘、是否能在
     * 忘记密码（未认证）时免密执行、格式化后密码是否回出厂默认 123456。失败则诚实提示用 PC 串口工具重置。
     * 成功后关盘、状态退回未初始化（走 Init 向导）。聊天/日志内存由门面 / deauth 监听清。
     */
    override suspend fun wipeAll(): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = LibJniFSShell.SFFormat(ROOT)
        if (ret != 0) {
            return@withContext Result.failure(
                IllegalStateException("格式化失败（SDK 可能不支持免密格式化），错误码=$ret；可用 PC 串口管理工具重置")
            )
        }
        runCatching { fsShell.SFCloseDisk() }
        _deviceStatus.value = DeviceInfo(isInitialized = false, status = UsbDeviceStatus.CONNECTED)
        Result.success(Unit)
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
    }
}
