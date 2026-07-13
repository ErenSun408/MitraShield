package com.example.midun.data.local

import android.content.Context
import android.provider.Settings
import com.example.midun.data.ExitClearPrefs
import com.example.midun.data.AuthOps
import com.example.midun.data.model.DeviceInfo
import com.example.midun.data.model.SessionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 无卡版认证/生命周期管理器（NC2.1）。真卡版 RealUsbManager 的纯软件孪生，
 * 实现 [AuthOps]，由 [com.example.midun.data.AccountManager] 门面路由。
 *
 * **无「插卡/开盘」概念**：真卡登录 = `SFOpenDiskEx(密码)` 开隐藏盘（密码即开盘凭证）。无卡把它拆成两件独立的事：
 * - **登录门禁**：密码经 PBKDF2 派生哈希存 `.auth`；[authenticate] 比对哈希放行（防他人在已解锁的手机上进 App）。
 * - **静态加密**：用户文件由 [LocalKeystore] 的 **Android Keystore 硬件 KEK** 保护（防 root/取证/备份导出）。
 *   登录成功后载入 keystore、解出 DEK 驻内存。
 *
 * **诚实边界**：密码是 App 访问门禁、硬件 KEK 是 at-rest 保护；二者独立（密码不参与包裹 DEK）。这与真卡
 * 「密码即开盘」不同——如实反映无卡取舍，UI 不宣称等同安全卡。
 *
 * **启动**：无插拔广播；[connect] 在 App 启动时把状态从 DISCONNECTED 直接置 CONNECTED（按 `.auth` 是否存在
 * 判已初始化），供 Splash 路由到登录 / 初始化向导。容量取手机存储（`filesDir` 可用/总量）。
 */
@Singleton
class LocalAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileSystem: LocalFileSystem,
    private val keystore: LocalKeystore,
    private val identity: LocalDeviceIdentity
) : AuthOps {

    private val _deviceStatus = MutableStateFlow(DeviceInfo())
    override val deviceStatus: StateFlow<DeviceInfo> = _deviceStatus.asStateFlow()

    /** 本次会话的登录凭证（认证/初始化成功时记，登出/恢复出厂清）。供 [verifyPassword] 免读盘二次校验。 */
    @Volatile
    private var session: AuthRecord? = null

    /**
     * 启动连接（对应真卡 connectUsb，但无卡设备恒在）：探测是否已初始化（`.auth` 存在），状态置 CONNECTED。
     * deviceId = 本机稳定 id。总是成功。
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        val initialized = readAuth() != null
        _deviceStatus.value = DeviceInfo(
            isInitialized = initialized,
            deviceId = identity.deviceId(),
            status = SessionStatus.CONNECTED,
            boundPhoneId = readBoundId()
        )
        Result.success(Unit)
    }

    /**
     * 初始化：写登录凭证 `.auth`（PBKDF2 盐+哈希）、生成并落盘 keystore（硬件 KEK 包裹全新 DEK）、可选绑定本机。
     * 完成后 lock keystore + 清会话（回「已初始化、未认证」态），登录时再解锁——与真卡「init 后关盘、登录重开」对齐。
     */
    override suspend fun initDevice(password: String, bindDevice: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2(password, salt)
            if (!writeAuth(salt, hash)) {
                return@withContext Result.failure(IllegalStateException("初始化写入失败，请重试"))
            }
            // keystore：生成全新 DEK/KEK，原始 blob 落盘。失败必须让 init 失败——否则留下「已初始化却无 keystore」。
            if (!fileSystem.saveKeystoreRaw(keystore.createNew())) {
                fileSystem.streamDelete(AUTH_PATH)
                keystore.lock()
                return@withContext Result.failure(IllegalStateException("密钥库写入失败，请重试"))
            }
            val boundId = if (bindDevice) androidId().takeIf { writeBoundId(it) } else null
            keystore.lock() // init 后回未认证态，DEK 不驻留——登录时重新载入
            session = null
            _deviceStatus.value = _deviceStatus.value.copy(
                isInitialized = true,
                status = SessionStatus.CONNECTED,
                deviceId = identity.deviceId(),
                boundPhoneId = boundId
            )
            Result.success(Unit)
        }

    /**
     * 认证（登录）：校验 PBKDF2 密码哈希 → 载入 keystore 解出 DEK → 绑定校验 → 退出自动清理补清 → AUTHENTICATED。
     * keystore 缺失/损坏 → 拒登，不静默跑明文。
     */
    override suspend fun authenticate(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val record = readAuth()
            ?: return@withContext Result.failure(IllegalStateException("尚未初始化"))
        if (!constantTimeEquals(pbkdf2(password, record.salt), record.hash)) {
            return@withContext Result.failure(IllegalStateException("密码错误"))
        }
        val boundId = readBoundId()
        if (boundId != null && boundId != androidId()) {
            return@withContext Result.failure(IllegalStateException("此账户已绑定其他设备，无法在本机登录"))
        }
        if (!keystore.load(fileSystem.loadKeystoreRaw())) {
            keystore.lock()
            return@withContext Result.failure(IllegalStateException("密钥库缺失或损坏，请恢复出厂后重新初始化"))
        }
        session = record
        // 退出自动清理「下次登录补清」：keystore 已解锁、AUTHENTICATED 尚未置位（ChatRepository 未加载）→ 无竞态。
        val exitClear = readExitClearPrefs()
        if (exitClear.clearContacts) fileSystem.streamDelete(CHAT_SIDECAR)
        if (exitClear.clearFiles) fileSystem.clear()
        val (total, free) = capacity()
        _deviceStatus.value = _deviceStatus.value.copy(
            status = SessionStatus.AUTHENTICATED,
            deviceId = identity.deviceId(),
            totalBytes = total,
            freeBytes = free,
            boundPhoneId = boundId
        )
        Result.success(Unit)
    }

    /** 免读盘二次校验（登录后敏感操作确认）：比对会话凭证。 */
    override fun verifyPassword(password: String): Boolean {
        val s = session ?: return false
        return constantTimeEquals(pbkdf2(password, s.salt), s.hash)
    }

    /** 退出登录：锁 keystore（清内存 DEK），状态退回 CONNECTED。 */
    override fun logout() {
        keystore.lock()
        session = null
        _deviceStatus.value = _deviceStatus.value.copy(status = SessionStatus.CONNECTED)
    }

    /**
     * 恢复出厂 / 整体清除：清所有用户文件 + 侧车（聊天/日志/绑定/退出偏好）+ 登录凭证 + 销毁 keystore（含硬件 KEK
     * 别名），回「未初始化」态 → Splash 走初始化向导。数据不可复原（本地删除不抗取证，UI 不夸大）。
     */
    override suspend fun wipeAll(): Result<Unit> = withContext(Dispatchers.IO) {
        fileSystem.clear() // 删所有文件夹/文件/meta
        listOf(CHAT_SIDECAR, OPLOG_SIDECAR, BIND_PATH, EXITCLEAR_PATH, AUTH_PATH).forEach {
            fileSystem.streamDelete(it)
        }
        keystore.destroy() // 抹 DEK + 删 Android Keystore KEK 别名
        session = null
        _deviceStatus.value = DeviceInfo(
            isInitialized = false,
            status = SessionStatus.CONNECTED,
            deviceId = identity.deviceId()
        )
        Result.success(Unit)
    }

    /** 一键清理：只清用户文件（保留登录态/密码/绑定/keystore）。聊天/日志由门面清。 */
    override suspend fun wipeUserData(): Result<Unit> = fileSystem.clear().also { refreshCapacity() }

    override suspend fun updateBinding(bind: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (bind) {
            val id = androidId()
            if (!writeBoundId(id)) return@withContext Result.failure(IllegalStateException("绑定写入失败，请重试"))
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = id)
        } else {
            if (!fileSystem.streamDelete(BIND_PATH)) return@withContext Result.failure(IllegalStateException("解绑失败，请重试"))
            _deviceStatus.value = _deviceStatus.value.copy(boundPhoneId = null)
        }
        Result.success(Unit)
    }

    /** 密钥更新：轮换硬件 KEK 重包不变的 DEK，安全覆盖 keystore blob。DEK 不变 → 文件不丢。需已认证。 */
    override suspend fun updateKey(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_deviceStatus.value.status != SessionStatus.AUTHENTICATED) {
            return@withContext Result.failure(IllegalStateException("请先登录后再更新密钥"))
        }
        val blob = keystore.rewrap()
            ?: return@withContext Result.failure(IllegalStateException("密钥库未解锁，无法更新密钥"))
        if (!fileSystem.rewriteKeystoreRaw(blob)) {
            return@withContext Result.failure(IllegalStateException("密钥库写入失败，密钥未更新"))
        }
        Result.success(Unit)
    }

    override suspend fun getExitClearPrefs(): ExitClearPrefs = withContext(Dispatchers.IO) { readExitClearPrefs() }

    override suspend fun setExitClearPrefs(prefs: ExitClearPrefs): Result<Unit> = withContext(Dispatchers.IO) {
        val json = JSONObject().put("contacts", prefs.clearContacts).put("files", prefs.clearFiles).toString()
        if (fileSystem.writeFile(EXITCLEAR_PATH, json.byteInputStream()).isFailure) {
            return@withContext Result.failure(IllegalStateException("设置写入失败，请重试"))
        }
        Result.success(Unit)
    }

    /** 本机稳定设备 id（供 P2P deviceSn，替代真卡 SN）。 */
    fun deviceId(): String = identity.deviceId()

    /** 文件增删后刷新「已用空间」（重读手机存储容量）。仅已认证时有效。 */
    fun refreshCapacity() {
        if (_deviceStatus.value.status != SessionStatus.AUTHENTICATED) return
        val (total, free) = capacity()
        _deviceStatus.value = _deviceStatus.value.copy(totalBytes = total, freeBytes = free)
    }

    // —— 凭证 `.auth` 读写（PBKDF2 盐 + 哈希，原始字节；`.` 前缀根级文件不加密）——

    private data class AuthRecord(val salt: ByteArray, val hash: ByteArray)

    private fun writeAuth(salt: ByteArray, hash: ByteArray): Boolean {
        val blob = ByteArray(AUTH_MAGIC.size + 1 + SALT_BYTES + HASH_BYTES)
        System.arraycopy(AUTH_MAGIC, 0, blob, 0, AUTH_MAGIC.size)
        blob[AUTH_MAGIC.size] = AUTH_VERSION
        System.arraycopy(salt, 0, blob, AUTH_MAGIC.size + 1, SALT_BYTES)
        System.arraycopy(hash, 0, blob, AUTH_MAGIC.size + 1 + SALT_BYTES, HASH_BYTES)
        return fileSystem.writeFile(AUTH_PATH, blob.inputStream()).isSuccess
    }

    private fun readAuth(): AuthRecord? {
        val out = ByteArrayOutputStream()
        if (fileSystem.readFile(AUTH_PATH, out).isFailure) return null
        val blob = out.toByteArray()
        val expected = AUTH_MAGIC.size + 1 + SALT_BYTES + HASH_BYTES
        if (blob.size != expected) return null
        if (!AUTH_MAGIC.indices.all { blob[it] == AUTH_MAGIC[it] } || blob[AUTH_MAGIC.size] != AUTH_VERSION) return null
        val salt = blob.copyOfRange(AUTH_MAGIC.size + 1, AUTH_MAGIC.size + 1 + SALT_BYTES)
        val hash = blob.copyOfRange(AUTH_MAGIC.size + 1 + SALT_BYTES, expected)
        return AuthRecord(salt, hash)
    }

    // —— 绑定 `.bind` / 退出偏好 `.midun_exitclear.json` ——

    private fun writeBoundId(id: String): Boolean =
        fileSystem.writeFile(BIND_PATH, id.toByteArray(Charsets.UTF_8).inputStream()).isSuccess

    private fun readBoundId(): String? {
        val out = ByteArrayOutputStream()
        return if (fileSystem.readFile(BIND_PATH, out).isSuccess) {
            out.toString(Charsets.UTF_8.name()).trim().ifEmpty { null }
        } else null
    }

    private fun readExitClearPrefs(): ExitClearPrefs {
        val out = ByteArrayOutputStream()
        if (fileSystem.readFile(EXITCLEAR_PATH, out).isFailure) return ExitClearPrefs()
        return runCatching {
            val o = JSONObject(out.toString(Charsets.UTF_8.name()))
            ExitClearPrefs(o.optBoolean("contacts", false), o.optBoolean("files", false))
        }.getOrDefault(ExitClearPrefs())
    }

    // —— 工具 ——

    private fun capacity(): Pair<Long, Long> {
        val dir = context.filesDir
        return runCatching { dir.totalSpace to dir.usableSpace }.getOrDefault(0L to 0L)
    }

    private fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, HASH_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** 恒定时间比较（防哈希比对时序侧信道）。 */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private companion object {
        const val AUTH_PATH = "0:/.midun_auth"
        const val BIND_PATH = "0:/.bind"
        const val CHAT_SIDECAR = "0:/.midun_chat.json"
        const val OPLOG_SIDECAR = "0:/.midun_oplog.json"
        const val EXITCLEAR_PATH = "0:/.midun_exitclear.json"

        val AUTH_MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
        const val AUTH_VERSION: Byte = 1
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val PBKDF2_ITERS = 120_000
    }
}
