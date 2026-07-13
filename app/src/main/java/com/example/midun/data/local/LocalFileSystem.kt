package com.example.midun.data.local

import android.content.Context
import com.example.midun.crypto.FileContainer
import com.example.midun.crypto.FileCrypto
import com.example.midun.crypto.FileHeader
import com.example.midun.data.FileSystemOps
import com.example.midun.data.FileTypes
import com.example.midun.data.TransferCancelledException
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 无卡版隐私文件夹文件系统（NC1.3）。真卡版 RealFileSystem 的纯软件孪生：
 * **公共接口/方法签名逐一对齐**（drop-in 替换），把安全卡隐藏区 `LibJniFSShell.SFxxx` 原生原语换成
 * [RandomAccessFile]/[File]，**逐块 AES-GCM 加密写/读、视频预览随机读、导出容器逻辑整段复用**
 * （[FileCrypto]/[FileHeader]/[FileContainer] 与真卡共享，不改一字）。
 *
 * **虚拟路径模型**：沿用真卡的 `0:/` id 方案（`0:/工作文件`、`0:/工作文件/a.pdf`），使上层导航/路由/侧车
 * 路径**零改动**；`0:/` 虚拟根映射到 App 私有目录 `filesDir/vault/`。根级 `.` 前缀文件（keystore/meta/bind/
 * chat/oplog）= 侧车，列表隐藏、不加密。
 *
 * **加密来源**：DEK 来自 [LocalKeystore]（Android Keystore 硬件 KEK 包裹）而非卡；语义与真卡一致——
 * 隐私文件夹用户文件透明加解密，侧车/缓存原始字节。
 *
 * **并发**：不同文件各自独立句柄（单消费者），不需真卡那样的全局盘锁；仅拷贝策略缓存 [folderPolicies]
 * 做同步。落 `filesDir` 受 OS 沙箱保护，真正的抗提取靠 [LocalKeystore] 的硬件 KEK。
 */
@Singleton
class LocalFileSystem @Inject constructor(
    @ApplicationContext context: Context,
    private val keystore: LocalKeystore
) : FileSystemOps {

    /** `0:/` 虚拟根对应的真实目录。 */
    private val vault: File = File(context.filesDir, "vault").apply { mkdirs() }

    /** 句柄表：int 句柄 → RandomAccessFile（对齐真卡的 `SFOpen/SFCreate` 返回小整数句柄，>0 有效）。 */
    private val handles = ConcurrentHashMap<Int, RandomAccessFile>()
    private val handleSeq = AtomicInteger(0)

    /** 文件夹路径 → 拷贝策略（侧车缓存，懒加载）。 */
    private var folderPolicies: MutableMap<String, CopyPolicy>? = null

    override suspend fun getFolders(): List<FileItem> = withContext(Dispatchers.IO) {
        val policies = loadPolicies()
        listEntries(ROOT, dirs = true).map { name ->
            val path = ROOT + name
            FileItem(
                id = path,
                name = name,
                type = FileType.FOLDER,
                copyPolicy = policies[path] ?: CopyPolicy.NO_COPY
            )
        }
    }

    override suspend fun getFilesInFolder(folderId: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            listEntries("$folderId/", dirs = false).map { name ->
                val path = "$folderId/$name"
                val (size, type) = fileMeta(path)
                FileItem(id = path, name = name, type = type, size = size, parentId = folderId)
            }
        }

    override suspend fun getTotalFileCount(): Int = withContext(Dispatchers.IO) {
        listEntries(ROOT, dirs = true).sumOf { name ->
            listEntries("$ROOT$name/", dirs = false).size
        }
    }

    override suspend fun createFolder(name: String, policy: CopyPolicy): Result<FileItem> =
        withContext(Dispatchers.IO) {
            val path = ROOT + name
            if (!sfNewDir(path)) return@withContext Result.failure(IllegalStateException("创建文件夹失败"))
            setPolicy(path, policy)
            Result.success(FileItem(id = path, name = name, type = FileType.FOLDER, copyPolicy = policy))
        }

    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val path = "$folderId/$fileName"
        val result = openStream().use { input ->
            writeFile(path, input, MAX_IMPORT_BYTES, plaintextSize = size, isCancelled = isCancelled, onProgress = onProgress)
        }
        if (result.isFailure) sfDelete(path) // 删半成品（含取消）
        result.map {
            FileItem(id = path, name = fileName, type = FileTypes.fromExtension(fileName), size = it, parentId = folderId)
        }
    }

    override suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        readFile(fileId, output, isCancelled, onProgress)
    }

    override suspend fun readFileBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        readFile(fileId, out).map { out.toByteArray() }
    }

    /**
     * 加密导出（M12.5 类比）：读明文（[openCardStream] 解 DEK）+ 文件头，用导出口令经 [FileContainer] 重加密
     * 成 `.midun` 容器写到 [output]。返回明文字节数。**不关闭 [output]**（调用方负责）。
     */
    suspend fun exportFileEncrypted(
        fileId: String,
        fileName: String,
        output: OutputStream,
        passphrase: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (written: Long) -> Unit = {}
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val (size, type) = fileMeta(fileId)
            openCardStream(fileId).use { plain ->
                FileContainer.encrypt(plain, size, fileName, type, passphrase, output, isCancelled, onProgress)
            }
            size
        }
    }

    // —— 视频预览随机读原语（供 VaultFileDataSource 流式解密，句柄型 API 与真卡一致）——
    fun streamOpen(path: String): Int = sfOpen(path)
    fun streamSize(handle: Int): Long = sfSize(handle)
    fun streamSeek(handle: Int, pos: Long): Long = sfSeek(handle, pos)
    fun streamRead(handle: Int, buf: ByteArray, off: Int, len: Int): Int = sfRead(handle, buf, off, len)
    fun streamClose(handle: Int) = sfClose(handle)

    // —— 增量写原语（文件接收：网络块到达即落盘）——
    fun streamCreate(path: String): Int = sfCreate(path)
    fun streamWrite(handle: Int, buf: ByteArray, off: Int, len: Int): Int = sfWrite(handle, buf, off, len)
    fun streamDelete(path: String): Boolean = sfDelete(path)
    fun exists(path: String): Boolean = resolve(path).isFile
    fun fileSizeOrNull(path: String): Long? = resolve(path).takeIf { it.isFile }?.length()

    /** 跨目录移动（接收文件保存到隐私文件夹）。失败回 false，调用方回退 [copyWithinCard]。 */
    fun streamMove(fromPath: String, toPath: String): Boolean = sfRename(fromPath, toPath)

    /** 当前 DEK（供视频随机读解密用）；未解锁回 null。 */
    fun currentDek(): ByteArray? = keystore.dek()

    /**
     * 打开文件为流式 [InputStream]（发送隐私文件夹文件时按需读）。用户文件（文件头 MAGIC 命中）返回边读边
     * 解密的明文流；侧车/缓存/旧未加密文件返回原始字节流。调用方须 `use{}`。
     */
    fun openCardStream(path: String): InputStream {
        val handle = sfOpen(path)
        if (handle <= 0) throw IOException("打开文件失败：$path")
        val dek = keystore.dek()
        if (isUserFilePath(path) && dek != null) {
            val head = ByteArray(FileHeader.BYTES)
            val parsed = if (readFullyFromCard(handle, head)) FileHeader.parse(head) else null
            if (parsed != null) return decryptingCardStream(handle, parsed, dek)
            sfSeek(handle, 0) // 旧未加密文件：回卷读原始字节
        }
        return rawCardStream(handle)
    }

    private fun rawCardStream(handle: Int): InputStream = object : InputStream() {
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            when (val n = sfRead(handle, b, off, len)) {
                0 -> -1 // EOF
                in Int.MIN_VALUE..-1 -> throw IOException("读取文件失败")
                else -> n
            }
        override fun close() = sfClose(handle)
    }

    /** 边读边解密的明文流：逐块读密文 → DEK 解密 → 供明文字节。句柄已越过文件头。 */
    private fun decryptingCardStream(handle: Int, parsed: FileHeader.Parsed, dek: ByteArray): InputStream =
        object : InputStream() {
            private val size = parsed.plaintextSize
            private val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            private val reader = BufferedReaderOnHandle(handle, IO_CALL)
            private var chunkIndex = 0
            private var plain = ByteArray(0)
            private var plainPos = 0
            private val single = ByteArray(1)

            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (plainPos >= plain.size) {
                    if (chunkIndex >= totalChunks) return -1
                    val plainLen = minOf(CHUNK.toLong(), size - chunkIndex.toLong() * CHUNK).toInt().coerceAtLeast(0)
                    val cipher = ByteArray(plainLen + FileCrypto.GCM_TAG_BYTES)
                    if (!reader.readExact(cipher)) throw IOException("密文不足 @chunk$chunkIndex")
                    val isLast = chunkIndex == totalChunks - 1
                    plain = FileCrypto.decryptChunk(dek, parsed.fileNonce, chunkIndex, isLast, cipher)
                    plainPos = 0
                    chunkIndex++
                }
                val n = minOf(len, plain.size - plainPos)
                System.arraycopy(plain, plainPos, b, off, n)
                plainPos += n
                return n
            }

            override fun close() = sfClose(handle)
        }

    /** 流式复制（[streamMove] 失败时兜底）：64KB 分块读写。成功回 true。 */
    fun copyWithinCard(fromPath: String, toPath: String): Boolean {
        val rh = sfOpen(fromPath)
        if (rh <= 0) return false
        val wh = sfCreate(toPath)
        if (wh <= 0) { sfClose(rh); return false }
        try {
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = sfRead(rh, buf, 0, CHUNK)
                if (n < 0) return false
                if (n == 0) break
                if (sfWrite(wh, buf, 0, n) < 0) return false
            }
            return true
        } finally {
            sfClose(rh)
            sfClose(wh)
        }
    }

    override suspend fun moveFile(fileId: String, targetFolderId: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            val fileName = fileId.substringAfterLast('/')
            val currentParent = fileId.substringBeforeLast('/')
            if (currentParent == targetFolderId) {
                return@withContext Result.failure(IllegalStateException("文件已在该文件夹中"))
            }
            val newPath = "$targetFolderId/$fileName"
            if (exists(newPath)) {
                return@withContext Result.failure(IllegalStateException("目标文件夹已存在同名文件"))
            }
            if (!sfRename(fileId, newPath)) {
                if (!copyWithinCard(fileId, newPath)) {
                    return@withContext Result.failure(IllegalStateException("移动文件失败"))
                }
                sfDelete(fileId)
            }
            val (size, type) = fileMeta(newPath)
            Result.success(
                FileItem(id = newPath, name = fileName, type = type, size = size, parentId = targetFolderId)
            )
        }

    override suspend fun deleteFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (sfDelete(fileId)) Result.success(Unit)
        else Result.failure(IllegalStateException("删除文件失败"))
    }

    override suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            listEntries("$folderId/", dirs = false).forEach { sfDelete("$folderId/$it") }
            Result.success(Unit)
        }

    override suspend fun deleteFolder(folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        listEntries("$folderId/", dirs = false).forEach { sfDelete("$folderId/$it") }
        if (!sfRemoveDir(folderId)) return@withContext Result.failure(IllegalStateException("删除文件夹失败"))
        removePolicy(folderId)
        Result.success(Unit)
    }

    override suspend fun renameFolder(folderId: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val newPath = ROOT + newName
            if (!sfRename(folderId, newPath)) {
                return@withContext Result.failure(IllegalStateException("重命名文件夹失败"))
            }
            renamePolicyKey(folderId, newPath)
            Result.success(Unit)
        }

    override suspend fun renameFile(fileId: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val newPath = fileId.substringBeforeLast('/') + "/" + newName
            if (sfRename(fileId, newPath)) Result.success(Unit)
            else Result.failure(IllegalStateException("重命名文件失败"))
        }

    /** 整库清理：删所有文件夹及其内文件 + 抹侧车。 */
    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        listEntries(ROOT, dirs = true).forEach { folder ->
            val folderPath = ROOT + folder
            listEntries("$folderPath/", dirs = false).forEach { sfDelete("$folderPath/$it") }
            sfRemoveDir(folderPath)
        }
        sfDelete(META_PATH)
        folderPolicies = mutableMapOf()
        Result.success(Unit)
    }

    // —— 分块读写（导入/导出复用）——

    fun writeFile(
        path: String,
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        plaintextSize: Long? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ): Result<Long> {
        val dek = keystore.dek()
        if (dek != null && isUserFilePath(path)) {
            val size = plaintextSize
                ?: return Result.failure(IllegalStateException("加密写入缺少明文大小（plaintextSize）"))
            if (size > maxBytes) {
                return Result.failure(IllegalStateException("文件超过上限 ${maxBytes / (1024 * 1024)}MB"))
            }
            return writeEncrypted(path, input, size, dek, isCancelled, onProgress)
        }
        return writeRaw(path, input, maxBytes, isCancelled, onProgress)
    }

    private fun writeRaw(
        path: String, input: InputStream, maxBytes: Long, isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> {
        val handle = sfCreate(path)
        if (handle <= 0) return Result.failure(IllegalStateException("创建文件失败"))
        var total = 0L
        try {
            val buf = ByteArray(CHUNK)
            while (true) {
                if (isCancelled()) return Result.failure(TransferCancelledException())
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    return Result.failure(IllegalStateException("文件超过上限 ${maxBytes / (1024 * 1024)}MB"))
                }
                if (n > 0 && sfWrite(handle, buf, 0, n) < 0) {
                    return Result.failure(IllegalStateException("写入失败 @$total"))
                }
                onProgress(total)
            }
            return Result.success(total)
        } finally {
            sfClose(handle)
        }
    }

    /** 加密写入：写 [FileHeader]（明文大小 + fileNonce + 类型），再逐块 DEK AES-GCM 加密落盘。返回明文字节数。 */
    private fun writeEncrypted(
        path: String, input: InputStream, size: Long, dek: ByteArray,
        isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> {
        val handle = sfCreate(path)
        if (handle <= 0) return Result.failure(IllegalStateException("创建文件失败"))
        try {
            val fileNonce = FileCrypto.newFileNonce()
            val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            val block = ByteArray(IO_CALL + CHUNK + FileCrypto.GCM_TAG_BYTES)
            var blockLen = 0
            fun appendToBlock(bytes: ByteArray) {
                System.arraycopy(bytes, 0, block, blockLen, bytes.size)
                blockLen += bytes.size
                if (blockLen >= IO_CALL) { writeFullyToCard(handle, block, 0, blockLen); blockLen = 0 }
            }
            val firstWant = minOf(CHUNK.toLong(), size).toInt().coerceAtLeast(0)
            val firstPlain = ByteArray(firstWant)
            if (firstWant > 0) readFully(input, firstPlain)
            val fileType = FileTypes.detect(firstPlain, path.substringAfterLast('/'))
            appendToBlock(FileHeader.build(size, fileNonce, fileType))
            var done = 0L
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException()
                val plain = if (index == 0) firstPlain else {
                    val want = minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                    ByteArray(want).also { if (want > 0) readFully(input, it) }
                }
                val isLast = index == totalChunks - 1
                appendToBlock(FileCrypto.encryptChunk(dek, fileNonce, index, isLast, plain))
                done += plain.size
                onProgress(done)
            }
            if (blockLen > 0) writeFullyToCard(handle, block, 0, blockLen)
            return Result.success(size)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            sfClose(handle)
        }
    }

    private fun isUserFilePath(path: String): Boolean {
        if (!path.startsWith(ROOT)) return false
        val rel = path.removePrefix(ROOT)
        if (!rel.contains('/')) return false
        val name = rel.substringAfterLast('/')
        return name.isNotEmpty() && !name.startsWith(".")
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("源流提前结束 @$off/${buf.size}")
            off += n
        }
    }

    private fun writeFullyToCard(handle: Int, bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        var p = off
        val end = off + len
        while (p < end) {
            val w = sfWrite(handle, bytes, p, minOf(end - p, IO_CALL))
            if (w <= 0) throw IOException("写入失败/无进展 @$p/$end")
            p += w
        }
    }

    /** 缓冲读句柄：按 [IO_CALL] 大块读进内部缓冲，逐次精确取出小加密块（少 read 系统调用）。 */
    private inner class BufferedReaderOnHandle(private val handle: Int, capacity: Int) {
        private val buf = ByteArray(capacity)
        private var pos = 0
        private var lim = 0

        fun readExact(out: ByteArray): Boolean {
            var o = 0
            while (o < out.size) {
                if (pos >= lim) {
                    val n = sfRead(handle, buf, 0, buf.size)
                    if (n <= 0) return false
                    pos = 0; lim = n
                }
                val take = minOf(out.size - o, lim - pos)
                System.arraycopy(buf, pos, out, o, take)
                pos += take
                o += take
            }
            return true
        }
    }

    // —— App 层密钥库 raw I/O ——
    // keystore blob 存 wrappedDEK，须以**原始字节**落盘/读盘（它本身就是解密密钥，绝不能再被 DEK 加密）。

    fun saveKeystoreRaw(bytes: ByteArray): Boolean =
        writeFile(KEYSTORE_PATH, bytes.inputStream()).isSuccess

    /** 安全覆盖 keystore（密钥更新）：临时文件 → 旧文件改备份 → 临时就位 → 删备份，任一步崩溃仍有一份完整 blob。 */
    fun rewriteKeystoreRaw(bytes: ByteArray): Boolean {
        sfDelete(KEYSTORE_TMP)
        sfDelete(KEYSTORE_BAK)
        if (!writeFile(KEYSTORE_TMP, bytes.inputStream()).isSuccess) {
            sfDelete(KEYSTORE_TMP)
            return false
        }
        if (!sfRename(KEYSTORE_PATH, KEYSTORE_BAK)) {
            sfDelete(KEYSTORE_TMP); return false
        }
        if (!sfRename(KEYSTORE_TMP, KEYSTORE_PATH)) {
            sfRename(KEYSTORE_BAK, KEYSTORE_PATH) // 还原
            sfDelete(KEYSTORE_TMP)
            return false
        }
        sfDelete(KEYSTORE_BAK)
        return true
    }

    /** 原始读 keystore blob；缺失返回 null。自愈：主缺失但备份还在（rewrite 中断）→ 用备份并还原。 */
    fun loadKeystoreRaw(): ByteArray? {
        readKeystoreAt(KEYSTORE_PATH)?.let { return it }
        val bak = readKeystoreAt(KEYSTORE_BAK) ?: return null
        sfRename(KEYSTORE_BAK, KEYSTORE_PATH)
        return bak
    }

    private fun readKeystoreAt(path: String): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (readFile(path, out).isSuccess) out.toByteArray() else null
    }

    /**
     * 把文件流式读出到 [output]，64KB 分块。用户文件且已解锁 → 读文件头，MAGIC 命中则逐块 DEK 解密写明文；
     * 侧车/缓存/旧未加密文件 → 原始字节。返回读出字节数（加密文件=明文字节数）。
     */
    fun readFile(
        path: String, output: OutputStream,
        isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}
    ): Result<Long> {
        val handle = sfOpen(path)
        if (handle <= 0) return Result.failure(IllegalStateException("打开文件失败"))
        try {
            val dek = keystore.dek()
            if (isUserFilePath(path) && dek != null) {
                val head = ByteArray(FileHeader.BYTES)
                val parsed = if (readFullyFromCard(handle, head)) FileHeader.parse(head) else null
                if (parsed != null) return decryptTo(handle, parsed, dek, output, isCancelled, onProgress)
                sfSeek(handle, 0) // 旧未加密用户文件：回卷
            }
            var total = 0L
            val buf = ByteArray(CHUNK)
            while (true) {
                if (isCancelled()) return Result.failure(TransferCancelledException())
                val n = sfRead(handle, buf, 0, CHUNK)
                if (n < 0) return Result.failure(IllegalStateException("读取失败 @$total"))
                if (n == 0) break
                output.write(buf, 0, n)
                total += n
                onProgress(total)
            }
            return Result.success(total)
        } finally {
            sfClose(handle)
        }
    }

    private fun decryptTo(
        handle: Int, parsed: FileHeader.Parsed, dek: ByteArray, output: OutputStream,
        isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> {
        val size = parsed.plaintextSize
        val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        val reader = BufferedReaderOnHandle(handle, IO_CALL)
        var total = 0L
        return try {
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException()
                val plainLen = minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                val cipher = ByteArray(plainLen + FileCrypto.GCM_TAG_BYTES)
                if (!reader.readExact(cipher)) throw IOException("密文不足 @chunk$index")
                val isLast = index == totalChunks - 1
                val plain = FileCrypto.decryptChunk(dek, parsed.fileNonce, index, isLast, cipher)
                output.write(plain)
                total += plain.size
                onProgress(total)
            }
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // —— 内部工具 ——

    private fun listEntries(path: String, dirs: Boolean): List<String> {
        val entries = resolve(path).listFiles() ?: return emptyList()
        return entries.filter { it.isDirectory == dirs && !it.name.startsWith(".") }.map { it.name }
    }

    /** 读单文件的明文大小 + 类型（读 [FileHeader]）。头缺失/MAGIC 不符 → 原始大小 + 扩展名判型。失败回 (0, OTHER)。 */
    private fun fileMeta(path: String): Pair<Long, FileType> {
        val handle = sfOpen(path)
        if (handle <= 0) return 0L to FileType.OTHER
        try {
            val raw = sfSize(handle)
            if (raw >= FileHeader.BYTES) {
                val head = ByteArray(FileHeader.BYTES)
                if (readFullyFromCard(handle, head)) {
                    FileHeader.parse(head)?.let { return it.plaintextSize to it.fileType }
                }
            }
            return (if (raw >= 0) raw else 0L) to FileTypes.fromExtension(path.substringAfterLast('/'))
        } finally {
            sfClose(handle)
        }
    }

    private fun readFullyFromCard(handle: Int, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = sfRead(handle, buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    // —— 底层文件原语（对齐真卡 SFxxx 语义：句柄 >0 有效、read 返回字节数 <0 失败 / 0 EOF）——

    private fun resolve(path: String): File = File(vault, path.removePrefix(ROOT))

    private fun sfOpen(path: String): Int {
        val f = resolve(path)
        if (!f.isFile) return 0
        return registerHandle(runCatching { RandomAccessFile(f, "r") }.getOrNull() ?: return 0)
    }

    private fun sfCreate(path: String): Int {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        val raf = runCatching { RandomAccessFile(f, "rw").apply { setLength(0) } }.getOrNull() ?: return 0
        return registerHandle(raf)
    }

    private fun registerHandle(raf: RandomAccessFile): Int {
        val h = handleSeq.incrementAndGet()
        handles[h] = raf
        return h
    }

    /** 读：返回字节数；EOF 返回 0（真卡语义）；坏句柄/错误 <0。 */
    private fun sfRead(handle: Int, buf: ByteArray, off: Int, len: Int): Int {
        val raf = handles[handle] ?: return -1
        return runCatching { raf.read(buf, off, len).let { if (it < 0) 0 else it } }.getOrDefault(-1)
    }

    /** 写：返回写入字节数；坏句柄/错误 <0。 */
    private fun sfWrite(handle: Int, buf: ByteArray, off: Int, len: Int): Int {
        val raf = handles[handle] ?: return -1
        return runCatching { raf.write(buf, off, len); len }.getOrDefault(-1)
    }

    private fun sfSeek(handle: Int, pos: Long): Long {
        val raf = handles[handle] ?: return -1
        return runCatching { raf.seek(pos); pos }.getOrDefault(-1)
    }

    private fun sfSize(handle: Int): Long {
        val raf = handles[handle] ?: return -1
        return runCatching { raf.length() }.getOrDefault(-1)
    }

    private fun sfClose(handle: Int) {
        runCatching { handles.remove(handle)?.close() }
    }

    private fun sfDelete(path: String): Boolean = runCatching { resolve(path).delete() }.getOrDefault(false)

    private fun sfRename(fromPath: String, toPath: String): Boolean = runCatching {
        val to = resolve(toPath)
        to.parentFile?.mkdirs()
        resolve(fromPath).renameTo(to)
    }.getOrDefault(false)

    /** 新建目录：真卡 SFNewDir 语义——新建成功 true；已存在（[File.mkdirs] 返回 false）→ false → 重名拒绝。 */
    private fun sfNewDir(path: String): Boolean = runCatching { resolve(path).mkdirs() }.getOrDefault(false)

    private fun sfRemoveDir(path: String): Boolean = runCatching { resolve(path).delete() }.getOrDefault(false)

    // —— 拷贝策略侧车（落盘）——

    private fun loadPolicies(): Map<String, CopyPolicy> {
        folderPolicies?.let { return it }
        val map = mutableMapOf<String, CopyPolicy>()
        runCatching {
            val sb = ByteArrayOutputStream()
            if (readFile(META_PATH, sb).isSuccess) {
                val json = JSONObject(sb.toString(Charsets.UTF_8.name()))
                val folders = json.optJSONObject("folders") ?: JSONObject()
                for (key in folders.keys()) {
                    map[key] = CopyPolicy.entries.getOrElse(folders.getInt(key)) { CopyPolicy.NO_COPY }
                }
            }
        }
        folderPolicies = map
        return map
    }

    @Synchronized
    private fun setPolicy(folderPath: String, policy: CopyPolicy) {
        loadPolicies()
        folderPolicies!![folderPath] = policy
        savePolicies()
    }

    @Synchronized
    private fun removePolicy(folderPath: String) {
        loadPolicies()
        if (folderPolicies!!.remove(folderPath) != null) savePolicies()
    }

    @Synchronized
    private fun renamePolicyKey(oldPath: String, newPath: String) {
        loadPolicies()
        folderPolicies!!.remove(oldPath)?.let {
            folderPolicies!![newPath] = it
            savePolicies()
        }
    }

    private fun savePolicies() {
        val folders = JSONObject()
        folderPolicies?.forEach { (path, policy) -> folders.put(path, policy.ordinal) }
        val bytes = JSONObject().put("folders", folders).toString().toByteArray(Charsets.UTF_8)
        runCatching { writeFile(META_PATH, bytes.inputStream()) }
    }

    private companion object {
        const val ROOT = "0:/"
        const val META_PATH = "0:/.midun_meta.json"
        const val KEYSTORE_PATH = "0:/.midun_keystore"
        const val KEYSTORE_TMP = "0:/.midun_keystore.tmp"
        const val KEYSTORE_BAK = "0:/.midun_keystore.bak"
        const val CHUNK = FileCrypto.CHUNK_PLAIN_BYTES
        const val IO_CALL = 128 * 1024
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024
    }
}
