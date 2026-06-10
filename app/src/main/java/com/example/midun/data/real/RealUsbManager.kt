package com.example.midun.data.real

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import seczure.device.usb.USBStorageHelper
import seczure.fsudisk.fsshell.FSShellInstance
import seczure.fsudisk.fsshell.LibJniFSShell

/**
 * 真实 FSShell 安全卡管理器（M11.2）。封装两层打开：
 * - **USB 层**：`USBStorageHelper` 申请权限 + 拿设备句柄（不验密码）。
 * - **FSShell 盘层**：`SFOpenDiskEx(diskName, pwd)` 用密码打开隐藏区盘。
 *
 * ⚠️ **当前为骨架**：只实现「打开真卡」通路，**尚未接入 app**（Mock→Real 切换在后续子阶段，需先
 * 抽公共接口 + Hilt 绑定）。本地只保证**编译 + API 用法正确**；USB 权限弹框、`SFOpenDiskEx` 实际
 * 打开均需真卡 + OTG 真机联调。
 *
 * **关键事实（区别于 [com.example.midun.data.mock.MockUsbManager]）**：真 SDK 里「打开设备」与
 * 「密码认证」是**同一调用** `SFOpenDiskEx(diskName, pwd)`——`USBStorageHelper.Open` 只拿 USB 句柄、
 * 不验密码，`SFOpenDiskEx` 才用密码打开 FSShell 盘。故 Mock 期分离的 simulateInsert/authenticate
 * 在真 SDK 下会合流；UI 接入时（M11.3）需处理这一语义差异。
 *
 * **R8 注意**：`LibJniFSShell`/`USBStorageHelper` 含 JNI native 方法，release 混淆必须 keep（M11.6）。
 */
@Singleton
class RealUsbManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fsShell: LibJniFSShell = FSShellInstance.getLibFSShellInstance()
    private var usbHelper: USBStorageHelper? = null

    /**
     * 打开真卡：枚举 USB → 申请权限并 `Open` 拿句柄 → 拼「外部设备」diskName →
     * `SFOpenDiskEx` 用密码打开 FSShell 盘。成功返回 [Result.success]。
     *
     * USB 权限异步：首次 `Open` 触发系统权限弹框，结果回调走 `PermissionHandler`
     * (`MSG_USB_PERMISSION`，arg1==1 取得 / 0 取消)。用户授权后需**重试** `Open`——真机时序细节
     * 待联调，此处先单次尝试 + 未授权时返回失败提示。
     *
     * [password] M11.2 暂用默认 "123456" 验证可打开；SHA256 哈希 + 认证 UI 集成见 M11.3。
     */
    suspend fun openDevice(password: String = DEFAULT_PASSWORD): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val helper = USBStorageHelper(context, context.packageName).also { usbHelper = it }
            // USB 权限回调（主线程 Handler）：arg1==1 取得权限、0 取消。骨架阶段仅消费消息。
            helper.PermissionHandler = Handler(Looper.getMainLooper(), Handler.Callback { true })

            val list = helper.GetList()
            if (list.count == 0) {
                return@withContext Result.failure(IllegalStateException("未找到 USB 设备"))
            }
            val name = list.GetName(0) // 设备路径，如 "001.002"

            // 首次 Open 会自动申请权限；未授权时返回 false（需用户在系统弹框授权后重试）。
            if (!helper.Open(name)) {
                return@withContext Result.failure(
                    IllegalStateException("打开 USB 失败（可能未授权，请在系统弹框中允许后重试）")
                )
            }

            // 外部设备方式打开（兼容华为等严格权限系统）：Open 成功后属性才有效。
            val ret = fsShell.SFOpenDiskEx(buildExternalDiskName(helper, name), password)
            if (ret == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("SFOpenDiskEx 失败，错误码=$ret"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 关闭真卡：先关 FSShell 盘再关 USB 句柄（对应拔卡/退出清理）。 */
    suspend fun closeDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { fsShell.SFCloseDisk() }
        runCatching { usbHelper?.Close() }
        usbHelper = null
        Result.success(Unit)
    }

    /** 读取真卡唯一序列号（M11.6 接 P2P deviceSn 用，先提供）。打开后 driveName 参数被忽略。 */
    fun getSerialNumber(): String? = runCatching { fsShell.SFDiskGetSN("") }.getOrNull()

    /**
     * 拼「外部设备」diskName（SDK android_4.0 严格权限系统方式，SDK 文档 3.2 节）：
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
    }
}
