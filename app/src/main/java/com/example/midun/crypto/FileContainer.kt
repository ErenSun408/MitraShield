package com.example.midun.crypto

import com.example.midun.data.FileTypes
import com.example.midun.data.TransferCancelledException
import com.example.midun.data.model.FileType
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 口令保护的便携导出容器（M12.5，`.midun`）。把隐私文件夹里的文件（明文）用**用户导出口令**派生的密钥重新
 * 加密成一个独立、自包含、可带走的容器：脱离安全卡与卡内 DEK，靠口令在别处（另一台装了本 App 的手机）也能
 * 解回明文。
 *
 * 与 [FileCrypto]（卡内 DEK 静态加密）正交：DEK 在卡隐藏区受卡密码门保护；本容器的密钥**只**由口令 + 容器内
 * 随机 salt 经 PBKDF2-HMAC-SHA256 派生，不落任何地方 → **口令是唯一钥匙**。
 *
 * 布局：
 * ```
 * MAGIC(4 "MDX1") ‖ VER(1) ‖ iterations(4 BE) ‖ salt(16) ‖ fileNonce(8) ‖ typeCode(1)
 *   ‖ plaintextSize(8 BE) ‖ nameLen(2 BE) ‖ name(UTF-8) ‖ encChunk*
 * encChunkI = FileCrypto.encryptChunk(派生密钥, fileNonce, i, isLast, 明文块)   // 复用卡内同款分块方案
 * ```
 *
 * **诚实定位**：安全性 = 用户口令强度 + PBKDF2 迭代成本；弱口令 = 弱保护，UI 不得吹「军工级」。纯
 * `javax.crypto`/`java.security`、无 Android 依赖 → JVM 可测（见 `FileContainerTest`）。PBKDF2 手写在
 * `HmacSHA256`（API1 可用）之上，避开 `SecretKeyFactory("PBKDF2WithHmacSHA256")` 的 API26 门槛（minSdk 24）。
 */
object FileContainer {

    /** "MDX1" = MiDun eXport。 */
    val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
    const val VERSION: Byte = 1
    const val EXTENSION = "midun"
    /** PBKDF2 迭代次数（OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议）；存进容器头 → 解密端按存的值跑。 */
    const val DEFAULT_ITERATIONS = 210_000
    const val SALT_BYTES = 16

    private const val CHUNK = FileCrypto.CHUNK_PLAIN_BYTES
    private const val TAG = FileCrypto.GCM_TAG_BYTES

    // —— 头部偏移（定长段 44B，之后接变长 name）——
    private const val ITER_OFFSET = 5                                    // MAGIC(4)+VER(1)
    private const val SALT_OFFSET = ITER_OFFSET + 4                      // 9
    private const val NONCE_OFFSET = SALT_OFFSET + SALT_BYTES            // 25
    private const val TYPE_OFFSET = NONCE_OFFSET + FileCrypto.FILE_NONCE_BYTES // 33
    private const val SIZE_OFFSET = TYPE_OFFSET + 1                      // 34
    private const val NAMELEN_OFFSET = SIZE_OFFSET + 8                   // 42
    private const val FIXED_HEADER_BYTES = NAMELEN_OFFSET + 2            // 44

    private val rng = SecureRandom()

    /** 解密结果元数据。 */
    class Meta(val originalName: String, val fileType: FileType, val plaintextSize: Long)

    /** 口令错误或容器损坏（GCM 校验失败）。与真实 IO 错误区分，UI 提示「口令错误」。 */
    class BadPassphraseException : IOException("口令错误或文件已损坏")

    /** 首字节是否为容器 MAGIC（导入流检测 `.midun`）。 */
    fun isContainer(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * 把 [input] 的 [plaintextSize] 字节明文加密成容器写入 [output]。每块查 [isCancelled]（命中抛
     * [TransferCancelledException]），[onProgress] 回报已处理明文字节。**不关闭任何流**（调用方负责）。
     */
    fun encrypt(
        input: InputStream,
        plaintextSize: Long,
        originalName: String,
        fileType: FileType,
        passphrase: String,
        output: OutputStream,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ) {
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val fileNonce = FileCrypto.newFileNonce()
        val key = deriveKey(passphrase, salt, DEFAULT_ITERATIONS)
        try {
            output.write(buildHeader(DEFAULT_ITERATIONS, salt, fileNonce, fileType, plaintextSize, originalName))
            val totalChunks = ((plaintextSize + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            var done = 0L
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException()
                val want = minOf(CHUNK.toLong(), plaintextSize - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                val plain = ByteArray(want)
                readFully(input, plain)
                output.write(FileCrypto.encryptChunk(key, fileNonce, index, index == totalChunks - 1, plain))
                done += want
                onProgress(done)
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * 解密 [input] 容器、写明文到 [output]，返回 [Meta]。口令错/损坏抛 [BadPassphraseException]；非容器/截断抛
     * [IOException]。每块查 [isCancelled]，[onProgress] 回报已写明文字节。**不关闭任何流**。
     */
    fun decrypt(
        input: InputStream,
        passphrase: String,
        output: OutputStream,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ): Meta {
        val fixed = ByteArray(FIXED_HEADER_BYTES)
        readFully(input, fixed)
        if (!isContainer(fixed) || fixed[MAGIC.size] != VERSION) throw IOException("不是有效的 .midun 容器")
        val iterations = getIntBE(fixed, ITER_OFFSET)
        val salt = fixed.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_BYTES)
        val fileNonce = fixed.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + FileCrypto.FILE_NONCE_BYTES)
        val fileType = FileTypes.fromCode(fixed[TYPE_OFFSET].toInt() and 0xFF)
        val plaintextSize = getLongBE(fixed, SIZE_OFFSET)
        val nameLen = getShortBE(fixed, NAMELEN_OFFSET)
        if (plaintextSize < 0 || iterations <= 0) throw IOException("容器头损坏")
        val nameBytes = ByteArray(nameLen)
        readFully(input, nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)

        val key = deriveKey(passphrase, salt, iterations)
        try {
            val totalChunks = ((plaintextSize + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            var done = 0L
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException()
                val plainLen = minOf(CHUNK.toLong(), plaintextSize - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                val cipher = ByteArray(plainLen + TAG)
                readFully(input, cipher)
                val plain = try {
                    FileCrypto.decryptChunk(key, fileNonce, index, index == totalChunks - 1, cipher)
                } catch (e: AEADBadTagException) {
                    throw BadPassphraseException() // 口令错 → 派生密钥错 → 首块 GCM 校验即失败
                }
                output.write(plain)
                done += plain.size
                onProgress(done)
            }
            return Meta(name, fileType, plaintextSize)
        } finally {
            key.fill(0)
        }
    }

    // —— PBKDF2-HMAC-SHA256（手写，dkLen=32=1 块；RFC 8018）——

    /** 由口令 + salt 派生 32 字节 AES-256 密钥。[iterations] 越大暴力破解越慢（成本可调）。 */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        // T_1 = U_1 xor U_2 xor ... xor U_c；U_1 = HMAC(salt ‖ INT_BE(1))，U_j = HMAC(U_{j-1})
        mac.update(salt)
        var u = mac.doFinal(intBE(1))
        val t = u.copyOf()
        for (c in 2..iterations) {
            u = mac.doFinal(u)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        return t.copyOf(FileCrypto.KEY_BYTES) // hLen=32=KEY_BYTES，正好一块
    }

    // —— 头部构造 + 字节工具 ——

    private fun buildHeader(
        iterations: Int, salt: ByteArray, fileNonce: ByteArray, fileType: FileType,
        plaintextSize: Long, name: String
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFFFF) { "文件名过长" }
        val h = ByteArray(FIXED_HEADER_BYTES + nameBytes.size)
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.size)
        h[MAGIC.size] = VERSION
        putIntBE(h, ITER_OFFSET, iterations)
        System.arraycopy(salt, 0, h, SALT_OFFSET, SALT_BYTES)
        System.arraycopy(fileNonce, 0, h, NONCE_OFFSET, FileCrypto.FILE_NONCE_BYTES)
        h[TYPE_OFFSET] = FileTypes.toCode(fileType).toByte()
        putLongBE(h, SIZE_OFFSET, plaintextSize)
        putShortBE(h, NAMELEN_OFFSET, nameBytes.size)
        System.arraycopy(nameBytes, 0, h, FIXED_HEADER_BYTES, nameBytes.size)
        return h
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("数据不足（容器被截断？）@$off/${buf.size}")
            off += n
        }
    }

    private fun intBE(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun putIntBE(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 24).toByte(); out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte(); out[off + 3] = v.toByte()
    }

    private fun getIntBE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF shl 24) or (b[off + 1].toInt() and 0xFF shl 16) or
            (b[off + 2].toInt() and 0xFF shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun putShortBE(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 8).toByte(); out[off + 1] = v.toByte()
    }

    private fun getShortBE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun putLongBE(out: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) out[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    private fun getLongBE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
