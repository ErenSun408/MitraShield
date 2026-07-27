package com.example.midun.data.real

/**
 * FSShell 返回码解读（错误码表见 SDK `Samples/Source/Projects/Windows/FSDemo_Console_VS2008_C#/FSShell/
 * FSErrorCodes.cs`，外部错误基址 `ERROR_CODE_EXTERNAL_BASE = 1000`）。
 *
 * **为什么需要这个**：`SFOpenDiskEx` 的非 0 返回原来被一律当成「密码不对」。密码只是众多失败原因之一——
 * 设备 IO 错、句柄无效、驱动通道不支持等同样返回非 0。把它们混为一谈会造成两个真实故障（2026-07-27
 * 鸿蒙 2.0 mate40 日志实证）：
 * ① **新卡被判成已初始化**：默认密码探测因驱动原因失败 → App 以为卡有密码 → 跳过初始化直接进登录页 →
 *    卡上真实密码还是默认密码，用户输什么都错，永远登不进去；
 * ② **登录失败一律报「密码错误」**：其实是盘根本没打开，用户对着一张打不开的卡反复试密码。
 *
 * 故这里只把**确定与密码无关**的码列为设备级错误（见 [isDeviceLevel]）——白名单而非黑名单：未知码保持
 * 原有的「按密码问题处理」，不会因为码表不全而误伤当前唯一能用的 driverMode=0 路径。
 */
object FsErrors {

    /** 外部错误基址（SDK `ERROR_CODE_EXTERNAL_BASE`）。 */
    private const val BASE = 1000

    // ── 物理设备层（BASE+50..60）────────────────────────────────────────────────
    private const val DEVICE_LIB_LOST = BASE + 50
    private const val DEVICE_LIB_ERROR = BASE + 51
    private const val DEVICE_NO_DRIVES = BASE + 52
    private const val DEVICE_IO_ERROR = BASE + 53
    private const val DEVICE_ACCESS_DENIED = BASE + 54
    private const val DEVICE_OPEN_FAIL = BASE + 55
    private const val DEVICE_INQUIRY_FAIL = BASE + 59
    private const val DEVICE_INVALID_VENDOR = BASE + 60

    // ── 外壳库 FSShell（BASE+100..121）──────────────────────────────────────────
    private const val SHELL_MEM_OUT = BASE + 100
    private const val SHELL_INVALID_HANDLE = BASE + 101
    private const val SHELL_INVALID_DRIVE = BASE + 102
    private const val SHELL_HANDLED_DRIVE = BASE + 103
    private const val SHELL_NOT_INIT_DRIVE = BASE + 104
    /** 密码错误或 ROOT 数据丢失——**唯一**能证明「密码不对」的码。 */
    const val SHELL_PASSWORD_ERROR = BASE + 105
    private const val SHELL_DISK_ERROR = BASE + 111
    private const val SHELL_DISK_NOT_FOUND = BASE + 112
    private const val SHELL_NO_DRIVES = BASE + 120
    private const val SHELL_NO_FSUDISK_DRIVES = BASE + 121

    /** SDK `ERROR_CODE_NOT_SUPPORT = 0xFFFFFFFF`，取到 Kotlin Int 即 -1（驱动通道不支持该操作）。 */
    private const val NOT_SUPPORT = -1

    /**
     * 该返回码是否为**设备/驱动级**失败（即「盘压根没打开」，与密码无关）。
     * 白名单：只列码表里明确与密码无关的项，未知码返回 false（沿用旧的「当密码问题处理」行为）。
     */
    fun isDeviceLevel(ret: Int): Boolean = ret in DEVICE_LEVEL_CODES

    private val DEVICE_LEVEL_CODES = setOf(
        DEVICE_LIB_LOST, DEVICE_LIB_ERROR, DEVICE_NO_DRIVES, DEVICE_IO_ERROR,
        DEVICE_ACCESS_DENIED, DEVICE_OPEN_FAIL, DEVICE_INQUIRY_FAIL, DEVICE_INVALID_VENDOR,
        SHELL_MEM_OUT, SHELL_INVALID_HANDLE, SHELL_INVALID_DRIVE, SHELL_HANDLED_DRIVE,
        SHELL_NOT_INIT_DRIVE, SHELL_DISK_ERROR, SHELL_DISK_NOT_FOUND,
        SHELL_NO_DRIVES, SHELL_NO_FSUDISK_DRIVES, NOT_SUPPORT
    )

    /** 返回码 → 给用户看的原因短句（面向非技术用户，不出现算法/库名）。 */
    fun describe(ret: Int): String = when (ret) {
        0 -> "成功"
        DEVICE_LIB_LOST, DEVICE_LIB_ERROR -> "设备驱动组件缺失或损坏"
        DEVICE_NO_DRIVES, SHELL_NO_DRIVES, SHELL_NO_FSUDISK_DRIVES -> "找不到安全卡"
        DEVICE_IO_ERROR -> "设备读写失败，请重新插拔安全卡"
        DEVICE_ACCESS_DENIED -> "没有设备访问权限"
        DEVICE_OPEN_FAIL -> "设备打开失败"
        DEVICE_INQUIRY_FAIL, DEVICE_INVALID_VENDOR -> "设备型号校验失败，可能不是本产品的安全卡"
        SHELL_MEM_OUT -> "内存不足"
        SHELL_INVALID_HANDLE, SHELL_INVALID_DRIVE -> "设备句柄失效，请重新插拔安全卡"
        SHELL_HANDLED_DRIVE -> "上一次连接尚未关闭，请重新插拔安全卡"
        SHELL_NOT_INIT_DRIVE -> "安全卡未初始化或初始化信息损坏"
        SHELL_PASSWORD_ERROR -> "密码错误"
        SHELL_DISK_ERROR, SHELL_DISK_NOT_FOUND -> "打开存储区失败"
        NOT_SUPPORT -> "当前驱动模式不支持该操作"
        else -> "未知错误"
    }
}
