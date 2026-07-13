package com.example.midun.data.local

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.midun.crypto.FileCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无卡版 App 层密钥库（NC1.2）。与真卡版 [com.example.midun.data.crypto.CardKeystore] 同样持有一枚 **DEK**
 * 加密用户文件内容（逐块 AES-GCM，复用 [FileCrypto]），但 **KEK 的保管方式根本不同**：
 *
 * - 真卡版：KEK 是随机 32 字节、**明存在 blob 里**——因为 blob 落在安全卡隐藏区（卡硬件 AES + 卡密码门保护），
 *   明存 KEK 无妨。
 * - 无卡版：blob 落 App 私有目录 `filesDir`，**绝不能明存 KEK**（root/取证/备份导出即全泄）。故 KEK 是
 *   **Android Keystore 里的一枚非导出 AES-256 密钥**，永不出 keystore 进程；blob 里只存 `wrappedDEK`
 *   （= KEK 对 DEK 做的 AES-GCM 密文 + IV）。
 *
 * **三级硬件强度**（[generateKek]）：`StrongBox`（旗舰机独立安全芯片）→ 失败退普通 → 真机走 TEE、
 * **模拟器自动走 keymaster 软件后端**。给模拟器留的「软件路」就是这一层,无需手搓 PBKDF2,且 KEK 仍不落盘明文。
 *
 * **密钥版本 / 轮换（[rewrap]，对应 M12.6「更新密钥」）**：KEK 在 keystore 里带版本别名 `midun_kek_<v>`。
 * 轮换时生成下一版别名密钥、用它重包**不变的 DEK**、返回新 blob（DEK 不变 → 文件不必重加密、不丢）。
 * **绝不在 rewrap 里删旧别名**——新 blob 尚未持久化前旧 blob 仍在盘上、须能解；旧别名统一由 [load] 成功后
 * 清理（此时确认当前版本可用，其余版本即废）。这样任何一步崩溃都还有一份可解的 (blob, KEK)。
 *
 * 接口对齐 [com.example.midun.data.crypto.CardKeystore]：`dek()/isUnlocked/createNew()/load()/rewrap()/lock()`，
 * 供 `LocalFileSystem`（读写透明加解密）与 `LocalAuthManager`（生命周期落盘/解锁）复用同一套调用。
 */
@Singleton
class LocalKeystore @Inject constructor() {

    @Volatile
    private var dek: ByteArray? = null

    /** 当前 KEK 版本（createNew/load 时置位，rewrap 据此推下一版）。 */
    @Volatile
    private var keyVersion: Int = 0

    val isUnlocked: Boolean get() = dek != null

    fun dek(): ByteArray? = dek

    /**
     * 初始化时调用：生成全新 DEK，生成 v1 KEK（新别名），用 KEK 包裹 DEK。DEK 入内存，返回待落盘的 blob。
     * 先清掉所有历史别名（恢复出厂/重装残留），保证干净起点。
     */
    fun createNew(): ByteArray {
        purgeAllAliases()
        val newDek = FileCrypto.randomKey()
        val version = 1
        val kek = generateKek(aliasFor(version))
        val blob = wrap(newDek, kek, version)
        dek = newDek
        keyVersion = version
        return blob
    }

    /**
     * 登录后调用：解析 [blob]、取对应版本的 KEK 从 keystore 解出 DEK 入内存。返回是否成功。
     * blob 为 null / 格式版本不符 / KEK 别名缺失 / 解密失败 → false（保持锁定）。
     * 成功后清理非当前版本的残留别名（[rewrap] 中断遗留）。
     */
    fun load(blob: ByteArray?): Boolean {
        val parsed = parse(blob) ?: return false
        val (version, iv, wrapped) = parsed
        val kek = loadKek(aliasFor(version)) ?: return false
        return runCatching { unwrap(wrapped, iv, kek) }
            .onSuccess {
                dek = it
                keyVersion = version
                purgeAliasesExcept(version)
            }
            .isSuccess
    }

    /**
     * 密钥更新（M12.6 类比）：DEK 不变，生成下一版 KEK 重新包裹，返回新 blob（由调用方安全覆盖旧 blob）。
     * 未解锁返回 null。旧别名不在此删，见类注释。
     */
    fun rewrap(): ByteArray? {
        val current = dek ?: return null
        val nextVersion = keyVersion + 1
        val kek = generateKek(aliasFor(nextVersion))
        val blob = wrap(current, kek, nextVersion)
        keyVersion = nextVersion
        return blob
    }

    /** 锁定（登出/恢复出厂/退到未认证）：抹内存 DEK。keystore 里的 KEK 别名保留（登录时再解锁）。 */
    fun lock() {
        dek?.fill(0)
        dek = null
    }

    /** 恢复出厂：抹 DEK + 删所有 KEK 别名（数据已不可解，别名无留存意义）。 */
    fun destroy() {
        lock()
        purgeAllAliases()
        keyVersion = 0
    }

    // —— KEK（Android Keystore 非导出 AES 密钥）——

    /** 生成一枚受硬件保护的 AES-256 KEK 存入 keystore（别名 [alias]）：StrongBox 优先，失败退普通后端。 */
    private fun generateKek(alias: String): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { return buildKek(alias, strongBox = true) }
            // StrongBoxUnavailableException 等 → 退普通后端（TEE / 模拟器软件 keymaster）。
        }
        return buildKek(alias, strongBox = false)
    }

    private fun buildKek(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey() // 生成即存入 AndroidKeyStore（alias）
    }

    private fun loadKek(alias: String): SecretKey? = runCatching {
        keyStore().getKey(alias, null) as? SecretKey
    }.getOrNull()

    /** 用 KEK 对 DEK 做 AES-GCM 加密 → 组 blob。IV 由 Cipher 自动生成（每次不同）。 */
    private fun wrap(dek: ByteArray, kek: SecretKey, version: Int): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, kek) }
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "GCM IV 长度应为 $IV_BYTES，实际 ${iv.size}" }
        val ciphertext = cipher.doFinal(dek) // 32B DEK → 32 + 16 tag = 48
        require(ciphertext.size == WRAPPED_BYTES) { "wrappedDEK 长度应为 $WRAPPED_BYTES，实际 ${ciphertext.size}" }
        val blob = ByteArray(BLOB_BYTES)
        System.arraycopy(MAGIC, 0, blob, 0, MAGIC.size)
        blob[MAGIC.size] = VERSION
        blob[VER_OFFSET] = version.toByte()
        System.arraycopy(iv, 0, blob, IV_OFFSET, IV_BYTES)
        System.arraycopy(ciphertext, 0, blob, WRAPPED_OFFSET, WRAPPED_BYTES)
        return blob
    }

    private fun unwrap(wrapped: ByteArray, iv: ByteArray, kek: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(wrapped)
    }

    /** 解析 blob → (version, iv, wrappedDEK)；MAGIC/版本/长度不符返回 null。 */
    private fun parse(blob: ByteArray?): Triple<Int, ByteArray, ByteArray>? {
        if (blob == null || blob.size != BLOB_BYTES) return null
        if (!MAGIC.indices.all { blob[it] == MAGIC[it] } || blob[MAGIC.size] != VERSION) return null
        val version = blob[VER_OFFSET].toInt() and 0xFF
        val iv = blob.copyOfRange(IV_OFFSET, IV_OFFSET + IV_BYTES)
        val wrapped = blob.copyOfRange(WRAPPED_OFFSET, WRAPPED_OFFSET + WRAPPED_BYTES)
        return Triple(version, iv, wrapped)
    }

    // —— KEK 别名管理 ——

    private fun aliasFor(version: Int): String = "$ALIAS_PREFIX$version"

    /** 删除所有 `midun_kek_*` 别名（createNew 起点清场 / destroy）。 */
    private fun purgeAllAliases() = runCatching {
        val ks = keyStore()
        ks.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach {
            runCatching { ks.deleteEntry(it) }
        }
    }

    /** 删除除当前版本外的 `midun_kek_*` 别名（load 成功后清理 rewrap 遗留）。 */
    private fun purgeAliasesExcept(version: Int) = runCatching {
        val keep = aliasFor(version)
        val ks = keyStore()
        ks.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) && it != keep }.forEach {
            runCatching { ks.deleteEntry(it) }
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "midun_kek_"
        const val GCM_TAG_BITS = 128

        // blob 格式：MAGIC(4) ‖ VERSION(1) ‖ keyVersion(1) ‖ IV(12) ‖ wrappedDEK(48) = 66 字节。
        // 注意：KEK 本身**不在** blob 里（在 AndroidKeyStore），与真卡版 97 字节含 KEK 的格式本质不同。
        val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val WRAPPED_BYTES = FileCrypto.KEY_BYTES + 16 // DEK(32) + GCM tag(16) = 48
        val VER_OFFSET = MAGIC.size + 1 // 5
        val IV_OFFSET = VER_OFFSET + 1 // 6
        val WRAPPED_OFFSET = IV_OFFSET + IV_BYTES // 18
        val BLOB_BYTES = WRAPPED_OFFSET + WRAPPED_BYTES // 66
    }
}
