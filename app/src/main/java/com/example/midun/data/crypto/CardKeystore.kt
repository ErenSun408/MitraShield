package com.example.midun.data.crypto

import com.example.midun.crypto.FileCrypto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 层文件密钥库（M12.1）。持有一对 **DEK/KEK**：
 * - `DEK`（数据加密密钥）真正加密用户文件内容（M12.2+ 在 [com.example.midun.data.real.RealFileSystem] 读写
 *   路径透明加解密）。认证后驻内存，锁定/拔卡清零。
 * - `KEK`（密钥加密密钥）包裹 DEK 成 `wrappedDEK`。两者 + 版本组成 keystore blob，落卡隐藏区
 *   `0:/.midun_keystore`（受卡密码门 + 卡硬件 AES 保护）。
 *
 * **DEK+KEK 两层的意义**：①「密钥更新」= 重生成 KEK 重包 DEK（[rewrap]），DEK 不变 → 文件不丢、不必重
 * 加密整盘；② 留作以后管理员托管/恢复码挂钩。**本层不增加保密性**（KEK 与 wrappedDEK 同在受密码保护的
 * 隐藏区）——卡 + 密码才是真保护，详见 M12 设计。
 *
 * **无卡依赖（避免 DI 环）**：本类只做内存状态 + 纯加密，不直接读写卡。keystore blob 的落卡/读卡由
 * [com.example.midun.data.real.RealUsbManager] 在 init/auth 生命周期里经 RealFileSystem 原始读写完成。
 * 故纯 `javax.crypto`/`java.security`，可 JVM 单测（见 `CardKeystoreTest`）。
 */
@Singleton
class CardKeystore @Inject constructor() {

    /** 解锁后的 DEK（认证成功有值；锁定/拔卡后 null）。@Volatile：加解密线程读、生命周期线程写。 */
    @Volatile
    private var dek: ByteArray? = null

    /** 是否已解锁（DEK 在内存）。 */
    val isUnlocked: Boolean get() = dek != null

    /** 当前 DEK（供 RealFileSystem 加解密用，M12.2+）；未解锁返回 null。 */
    fun dek(): ByteArray? = dek

    /**
     * 初始化时（卡设新密码后、关盘前）调用：生成全新 DEK + KEK，DEK 入内存，返回待落卡的 keystore blob。
     */
    fun createNew(): ByteArray {
        val newDek = FileCrypto.randomKey()
        val kek = FileCrypto.randomKey()
        dek = newDek
        return serialize(kek, FileCrypto.wrapKey(kek, newDek))
    }

    /**
     * 认证（开盘）后调用：解析 [blob]、用 KEK 解出 DEK 入内存。返回是否成功。
     * [blob] 为 null（旧卡无 keystore）/ 长度版本不符 / 解包失败 → false（保持锁定，由 M12.4 决定旧卡处置）。
     */
    fun load(blob: ByteArray?): Boolean {
        val (kek, wrapped) = parse(blob) ?: return false
        return runCatching { FileCrypto.unwrapKey(kek, wrapped) }
            .onSuccess { dek = it }
            .isSuccess
    }

    /**
     * 密钥更新（M12.6）：保留当前 DEK 不变，重生成 KEK 重新包裹，返回新 blob（落卡覆盖旧 keystore）。
     * 未解锁返回 null（无 DEK 可重包）。文件因 DEK 不变而**不需重加密、不丢**。
     */
    fun rewrap(): ByteArray? {
        val current = dek ?: return null
        val kek = FileCrypto.randomKey()
        return serialize(kek, FileCrypto.wrapKey(kek, current))
    }

    /** 锁定（登出 / 拔卡 / 恢复出厂）：抹掉内存中的 DEK。 */
    fun lock() {
        dek?.fill(0)
        dek = null
    }

    // —— keystore blob 二进制格式：MAGIC(4) ‖ VERSION(1) ‖ KEK(32) ‖ wrappedDEK(60) = 97 字节 ——
    // 用定长二进制（非 Base64/JSON）：既不依赖 android.util.Base64、又避开 java.util.Base64 的 API26 门槛
    // （minSdk 24），且 JVM 可测。

    private fun serialize(kek: ByteArray, wrappedDek: ByteArray): ByteArray {
        require(kek.size == FileCrypto.KEY_BYTES) { "KEK 长度应为 ${FileCrypto.KEY_BYTES}" }
        require(wrappedDek.size == WRAPPED_DEK_BYTES) { "wrappedDEK 长度应为 $WRAPPED_DEK_BYTES" }
        val blob = ByteArray(BLOB_BYTES)
        System.arraycopy(MAGIC, 0, blob, 0, MAGIC.size)
        blob[MAGIC.size] = VERSION
        System.arraycopy(kek, 0, blob, KEK_OFFSET, FileCrypto.KEY_BYTES)
        System.arraycopy(wrappedDek, 0, blob, WRAPPED_OFFSET, WRAPPED_DEK_BYTES)
        return blob
    }

    /** 解析 blob → (KEK, wrappedDEK)；格式/版本不符返回 null。 */
    private fun parse(blob: ByteArray?): Pair<ByteArray, ByteArray>? {
        if (blob == null || blob.size != BLOB_BYTES) return null
        if (!MAGIC.indices.all { blob[it] == MAGIC[it] } || blob[MAGIC.size] != VERSION) return null
        val kek = blob.copyOfRange(KEK_OFFSET, KEK_OFFSET + FileCrypto.KEY_BYTES)
        val wrapped = blob.copyOfRange(WRAPPED_OFFSET, WRAPPED_OFFSET + WRAPPED_DEK_BYTES)
        return kek to wrapped
    }

    private companion object {
        val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
        const val VERSION: Byte = 1
        /** wrap(KEK, 32B DEK) = IV(12) + 密文(32) + tag(16)。 */
        const val WRAPPED_DEK_BYTES = 12 + 32 + 16
        val KEK_OFFSET = MAGIC.size + 1 // 5
        val WRAPPED_OFFSET = KEK_OFFSET + FileCrypto.KEY_BYTES // 37
        val BLOB_BYTES = WRAPPED_OFFSET + WRAPPED_DEK_BYTES // 97
    }
}
