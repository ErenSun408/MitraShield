package com.example.midun.data.real

import com.example.midun.crypto.FileCrypto
import com.example.midun.crypto.FileHeader
import com.example.midun.data.FileSystemOps
import com.example.midun.data.crypto.CardKeystore
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import seczure.fsudisk.fsshell.FSShellInstance
import seczure.fsudisk.fsshell.LibJniFSShell

/**
 * 真实 FSShell 安全卡隐藏区文件系统（M11.4）。实现 [FileSystemOps]，前提是 [RealUsbManager] 已
 * `SFOpenDiskEx` 打开盘（认证通过）；本类不管打开/认证，只做隐藏区 CRUD。
 *
 * **隐藏区路径模型**：根 `0:/`；一级目录 = 隐私文件夹（id = 完整路径 `0:/工作文件`）；目录下的文件 =
 * `0:/工作文件/合同.pdf`（id = 完整路径，`parentId` = 文件夹路径）。仅展示一级文件夹 + 其内文件（与 Mock 两层结构对齐）。
 *
 * **元数据落卡**：原生 FS 不存「拷贝策略」这类 App 概念 → 落到隐藏区**侧车文件** [META_PATH]（JSON：
 * 文件夹路径 → CopyPolicy.ordinal）。`type` 按扩展名推断、`size` 用 `SFOpen`+`SFGetSize` 读、`source` 默认 import。
 *
 * **API 性质（javap 核实）**：`SFNewDir/SFRemoveDir/SFCreate/SFOpen/SFClose/SFDelete/SFRename/SFGetSize/`
 * `SFRead/SFWrite` 是**静态**方法（`LibJniFSShell.X`）；`GetFileList`/`SFGetFileList` 是实例方法。
 * 文件句柄 `>0` 有效；读/写返回字节数（`<0` 失败、`0` EOF）。列表项前缀 `0=`(目录)/`1=`(文件)，`;` 分隔。
 *
 * **并发**：隐藏区盘是单例全局状态，所有原生序列在 [fsShell] 上 `synchronized`（对齐官方 Demo）。
 * **真机依赖**：所有调用需真卡 + 已打开盘；本地只保证编译 + 逻辑正确，未做设备验证（M11.5/6 联调）。
 * 中文文件名编码经 `GetFileList` 走 UTF-16LE，写入路径直传 String（同官方 Demo），编码兼容性留真机确认。
 */
@Singleton
class RealFileSystem @Inject constructor(
    // App 层文件密钥库（M12.2）：用户文件写路径用其 DEK 加密；锁定/旧卡（dek=null）回退原始字节。
    private val cardKeystore: CardKeystore
) : FileSystemOps {

    private val fsShell: LibJniFSShell = FSShellInstance.getLibFSShellInstance()

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
                FileItem(
                    id = path,
                    name = name,
                    type = guessFileType(name),
                    size = fileSize(path),
                    parentId = folderId
                )
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
            val ret = synchronized(fsShell) { LibJniFSShell.SFNewDir(path) }
            if (ret != 0) return@withContext Result.failure(fsError("创建文件夹失败", ret))
            setPolicy(path, policy)
            Result.success(FileItem(id = path, name = name, type = FileType.FOLDER, copyPolicy = policy))
        }

    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        onProgress: (written: Long) -> Unit
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val path = "$folderId/$fileName"
        // M11.5.1：真实字节流式导入——64KB 分块写入隐藏区，100MB 上限兜底。
        // M12.2：用户文件路径 → writeFile 用 DEK 加密落卡（需明文大小写文件头，传 plaintextSize）。
        val result = openStream().use { input ->
            writeFile(path, input, MAX_IMPORT_BYTES, plaintextSize = size, onProgress = onProgress)
        }
        if (result.isFailure) synchronized(fsShell) { LibJniFSShell.SFDelete(path) } // 删半成品
        result.map {
            FileItem(id = path, name = fileName, type = guessFileType(fileName), size = it, parentId = folderId)
        }
    }

    override suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: OutputStream
    ): Result<Long> = withContext(Dispatchers.IO) {
        // fileId 即隐藏区完整路径；readFile 对加密用户文件逐块 DEK 解密写出明文（M12.3）。导出==原文。
        readFile(fileId, output)
    }

    override suspend fun readFileBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        readFile(fileId, out).map { out.toByteArray() }
    }

    // —— 视频预览随机读原语（供自定义 media3 DataSource 流式解密，不落整文件）。句柄 SFOpen 返回。 ——
    fun streamOpen(path: String): Int = synchronized(fsShell) { LibJniFSShell.SFOpen(path) }
    fun streamSize(handle: Int): Long = synchronized(fsShell) { LibJniFSShell.SFGetSize(handle) }
    fun streamSeek(handle: Int, pos: Long): Long = synchronized(fsShell) { LibJniFSShell.SFSeek64(handle, pos, 0) }
    fun streamRead(handle: Int, buf: ByteArray, off: Int, len: Int): Int =
        synchronized(fsShell) { LibJniFSShell.SFRead(handle, buf, off, len) }
    fun streamClose(handle: Int) { synchronized(fsShell) { LibJniFSShell.SFClose(handle) } }

    // —— 增量写原语（M11.5.3 文件接收：网络块到达即解密落卡，不攒整文件）。句柄 SFCreate 返回（>0 有效）。 ——
    fun streamCreate(path: String): Int = synchronized(fsShell) { LibJniFSShell.SFCreate(path) }
    fun streamWrite(handle: Int, buf: ByteArray, off: Int, len: Int): Int =
        synchronized(fsShell) { LibJniFSShell.SFWrite(handle, buf, off, len) }
    /** 同步删单文件（接收失败/取消删半成品；路径直传隐藏区完整路径）。 */
    fun streamDelete(path: String): Boolean = synchronized(fsShell) { LibJniFSShell.SFDelete(path) }
    /** 卡内文件是否存在（预览前判断缓存是否已被 TTL 清理 → 过期降级）。 */
    fun exists(path: String): Boolean = synchronized(fsShell) {
        val h = LibJniFSShell.SFOpen(path)
        if (h > 0) { LibJniFSShell.SFClose(h); true } else false
    }
    /** 卡内文件大小（字节）；不存在/读不到返回 null（缓存占用统计用）。 */
    fun fileSizeOrNull(path: String): Long? = synchronized(fsShell) {
        val h = LibJniFSShell.SFOpen(path)
        if (h <= 0) return null
        val sz = LibJniFSShell.SFGetSize(h)
        LibJniFSShell.SFClose(h)
        if (sz >= 0) sz else null
    }
    /** 跨目录移动（接收文件保存到隐私文件夹；SFRename 改完整路径）。失败回 false，调用方回退 [copyWithinCard]。 */
    fun streamMove(fromPath: String, toPath: String): Boolean =
        synchronized(fsShell) { LibJniFSShell.SFRename(fromPath, toPath) }

    /** 当前 DEK（M12.3：供 [com.example.midun.media.CardFileDataSource] 视频随机读解密用）；未解锁回 null。 */
    fun currentDek(): ByteArray? = cardKeystore.dek()

    /**
     * 打开卡内文件为流式 [InputStream]（M11.5.3：发送隐私文件夹文件时按需读，不把整文件读进内存）。
     * 持有 `SFOpen` 句柄直到 `close()`；调用方须 `use{}`。打开失败抛 [IOException]。
     *
     * **M12.3 解密分流**：用户文件（已加密，文件头 MAGIC 命中）返回边读边解密的明文流；元数据/缓存/旧未加密
     * 文件返回原始字节流（行为同 M11.5.3）。供 P2P 发送隐私文件夹文件时拿到明文（再由会话密钥重新加密上链）。
     */
    fun openCardStream(path: String): InputStream {
        val handle = synchronized(fsShell) { LibJniFSShell.SFOpen(path) }
        if (handle <= 0) throw IOException("打开卡内文件失败：$path")
        val dek = cardKeystore.dek()
        if (isUserFilePath(path) && dek != null) {
            val head = ByteArray(FileHeader.BYTES)
            val parsed = synchronized(fsShell) {
                if (readFullyFromCard(handle, head)) FileHeader.parse(head) else null
            }
            if (parsed != null) return decryptingCardStream(handle, parsed, dek)
            synchronized(fsShell) { LibJniFSShell.SFSeek64(handle, 0, 0) } // 旧未加密文件：回卷读原始字节
        }
        return rawCardStream(handle)
    }

    /** 原始字节卡内流（元数据/缓存/旧文件）。 */
    private fun rawCardStream(handle: Int): InputStream = object : InputStream() {
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            when (val n = streamRead(handle, b, off, len)) {
                0 -> -1 // EOF
                in Int.MIN_VALUE..-1 -> throw IOException("读取卡内文件失败")
                else -> n
            }
        override fun close() = streamClose(handle)
    }

    /** 边读边解密的卡内明文流（M12.3）：逐 64KB 密文块读出 → DEK 解密 → 供明文字节。句柄已越过文件头。 */
    private fun decryptingCardStream(handle: Int, parsed: FileHeader.Parsed, dek: ByteArray): InputStream =
        object : InputStream() {
            private val size = parsed.plaintextSize
            private val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
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
                    if (!synchronized(fsShell) { readFullyFromCard(handle, cipher) }) {
                        throw IOException("密文不足 @chunk$chunkIndex")
                    }
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

            override fun close() = streamClose(handle)
        }

    /** 卡内流式复制（[streamMove] 失败时兜底）：64KB 分块读写，不攒整文件在内存。成功回 true。 */
    fun copyWithinCard(fromPath: String, toPath: String): Boolean = synchronized(fsShell) {
        val rh = LibJniFSShell.SFOpen(fromPath)
        if (rh <= 0) return false
        val wh = LibJniFSShell.SFCreate(toPath)
        if (wh <= 0) { LibJniFSShell.SFClose(rh); return false }
        try {
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = LibJniFSShell.SFRead(rh, buf, 0, CHUNK)
                if (n < 0) return false
                if (n == 0) break
                if (LibJniFSShell.SFWrite(wh, buf, 0, n) < 0) return false
            }
            true
        } finally {
            LibJniFSShell.SFClose(rh)
            LibJniFSShell.SFClose(wh)
        }
    }

    override suspend fun moveFile(fileId: String, targetFolderId: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            // fileId/targetFolderId 均为隐藏区完整路径；保留原文件名，目标路径 = 目标文件夹/原名。
            val fileName = fileId.substringAfterLast('/')
            val currentParent = fileId.substringBeforeLast('/')
            if (currentParent == targetFolderId) {
                return@withContext Result.failure(IllegalStateException("文件已在该文件夹中"))
            }
            val newPath = "$targetFolderId/$fileName"
            if (exists(newPath)) {
                return@withContext Result.failure(IllegalStateException("目标文件夹已存在同名文件"))
            }
            // 优先跨目录 SFRename（原子、不搬字节）；失败回退卡内流式复制 + 删源。
            val renamed = synchronized(fsShell) { LibJniFSShell.SFRename(fileId, newPath) }
            if (!renamed) {
                if (!copyWithinCard(fileId, newPath)) {
                    return@withContext Result.failure(IllegalStateException("移动文件失败"))
                }
                synchronized(fsShell) { LibJniFSShell.SFDelete(fileId) }
            }
            Result.success(
                FileItem(
                    id = newPath,
                    name = fileName,
                    type = guessFileType(fileName),
                    size = fileSize(newPath),
                    parentId = targetFolderId
                )
            )
        }

    override suspend fun deleteFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (synchronized(fsShell) { LibJniFSShell.SFDelete(fileId) }) Result.success(Unit)
        else Result.failure(IllegalStateException("删除文件失败"))
    }

    override suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            synchronized(fsShell) {
                listEntries("$folderId/", dirs = false).forEach {
                    LibJniFSShell.SFDelete("$folderId/$it")
                }
            }
            Result.success(Unit)
        }

    override suspend fun deleteFolder(folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = synchronized(fsShell) {
            listEntries("$folderId/", dirs = false).forEach { LibJniFSShell.SFDelete("$folderId/$it") }
            LibJniFSShell.SFRemoveDir(folderId)
        }
        if (ret != 0) return@withContext Result.failure(fsError("删除文件夹失败", ret))
        removePolicy(folderId)
        Result.success(Unit)
    }

    override suspend fun renameFolder(folderId: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val newPath = ROOT + newName
            if (!synchronized(fsShell) { LibJniFSShell.SFRename(folderId, newPath) }) {
                return@withContext Result.failure(IllegalStateException("重命名文件夹失败"))
            }
            renamePolicyKey(folderId, newPath)
            Result.success(Unit)
        }

    override suspend fun renameFile(fileId: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val newPath = fileId.substringBeforeLast('/') + "/" + newName
            if (synchronized(fsShell) { LibJniFSShell.SFRename(fileId, newPath) }) Result.success(Unit)
            else Result.failure(IllegalStateException("重命名文件失败"))
        }

    /** 整卡清理（M11.5/6 用）：删所有文件夹及其内文件 + 抹侧车。 */
    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(fsShell) {
            listEntries(ROOT, dirs = true).forEach { folder ->
                val folderPath = ROOT + folder
                listEntries("$folderPath/", dirs = false).forEach {
                    LibJniFSShell.SFDelete("$folderPath/$it")
                }
                LibJniFSShell.SFRemoveDir(folderPath)
            }
            LibJniFSShell.SFDelete(META_PATH)
        }
        folderPolicies = mutableMapOf()
        Result.success(Unit)
    }

    // —— 64KB 分块读写原语（M11.5 选取器导入/导出复用）——

    /**
     * 把 [input] 流式写入卡内 [path]，64KB 分块，[onProgress] 回报累计字节。超 [maxBytes] 则中止、删半成品
     * 并失败（导入 100MB 上限兜底）。返回写入字节数。**不负责关闭 [input]**（调用方 `use`）。
     *
     * **M12.2 加密分流**：若 [path] 是用户文件（[isUserFilePath]）且密钥库已解锁（DEK 在内存），走
     * [writeEncrypted] 用 DEK 加密落卡——此时必须传 [plaintextSize]（写文件头）。否则（元数据/缓存侧车、
     * 旧卡未解锁）走原始字节写入，行为同 M11.5。
     */
    fun writeFile(
        path: String,
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        plaintextSize: Long? = null,
        onProgress: (Long) -> Unit = {}
    ): Result<Long> {
        val dek = cardKeystore.dek()
        if (dek != null && isUserFilePath(path)) {
            val size = plaintextSize
                ?: return Result.failure(IllegalStateException("加密写入缺少明文大小（plaintextSize）"))
            if (size > maxBytes) {
                return Result.failure(IllegalStateException("文件超过上限 ${maxBytes / (1024 * 1024)}MB"))
            }
            return writeEncrypted(path, input, size, dek, onProgress)
        }
        return writeRaw(path, input, maxBytes, onProgress)
    }

    /** 原始字节写入（元数据/缓存侧车、未解锁旧卡）：64KB 分块直写，不加密。 */
    private fun writeRaw(
        path: String, input: InputStream, maxBytes: Long, onProgress: (Long) -> Unit
    ): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFCreate(path)
        if (handle <= 0) return Result.failure(fsError("创建文件失败", handle))
        var total = 0L
        try {
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    // finally 负责关句柄；半成品由 importFile 失败兜底删除。
                    return Result.failure(IllegalStateException("文件超过上限 ${maxBytes / (1024 * 1024)}MB"))
                }
                if (n > 0 && LibJniFSShell.SFWrite(handle, buf, 0, n) < 0) {
                    return Result.failure(IllegalStateException("写入失败 @$total"))
                }
                onProgress(total)
            }
            Result.success(total)
        } finally {
            LibJniFSShell.SFClose(handle)
        }
    }

    /**
     * 加密写入（M12.2）：写 [FileHeader]（含明文大小 + fileNonce），再逐 64KB 明文块用 DEK 做 AES-GCM 分块
     * 加密落卡。返回**明文字节数**（供 FileItem.size 用）。源流提前结束（实际 < [size]）则失败。
     */
    private fun writeEncrypted(
        path: String, input: InputStream, size: Long, dek: ByteArray, onProgress: (Long) -> Unit
    ): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFCreate(path)
        if (handle <= 0) return Result.failure(fsError("创建文件失败", handle))
        try {
            val fileNonce = FileCrypto.newFileNonce()
            writeFullyToCard(handle, FileHeader.build(size, fileNonce))
            val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            var done = 0L
            for (index in 0 until totalChunks) {
                val want = minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                val plain = ByteArray(want)
                if (want > 0) readFully(input, plain)
                val isLast = index == totalChunks - 1
                writeFullyToCard(handle, FileCrypto.encryptChunk(dek, fileNonce, index, isLast, plain))
                done += want
                onProgress(done)
            }
            Result.success(size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            LibJniFSShell.SFClose(handle)
        }
    }

    /** 用户文件判别（M12.2 加密范围）：`0:/<文件夹>/<名>` 且名非 `.` 前缀 = 用户文件（需加密）；
     * 根级 `.` 前缀文件（keystore/meta/bind/chat/oplog 及 .recv_/.sent_ 缓存）一律原始字节、不加密。 */
    private fun isUserFilePath(path: String): Boolean {
        if (!path.startsWith(ROOT)) return false
        val rel = path.removePrefix(ROOT)
        if (!rel.contains('/')) return false // 根级文件（含全部元数据/缓存侧车）
        val name = rel.substringAfterLast('/')
        return name.isNotEmpty() && !name.startsWith(".")
    }

    /** 从 [input] 精确读满 [buf]（read 可能短读）；流提前结束抛 [IOException]。 */
    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("源流提前结束 @$off/${buf.size}")
            off += n
        }
    }

    /** 把 [bytes] 全量写入卡句柄（SFWrite 可能短写）；失败/无进展抛 [IOException]。须在 fsShell 锁内调用。 */
    private fun writeFullyToCard(handle: Int, bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val w = LibJniFSShell.SFWrite(handle, bytes, off, bytes.size - off)
            if (w <= 0) throw IOException("写入卡失败/无进展 @$off/${bytes.size}")
            off += w
        }
    }

    // —— App 层密钥库 raw I/O（M12.1）——
    // keystore 存 DEK/KEK，必须以**原始字节**落卡/读卡：它本身就是「解密用户文件的密钥」，绝不能再被 DEK
    // 加密（鸡生蛋）。M12.2 给 writeFile/readFile 加 DEK 透明加解密时，[KEYSTORE_PATH] 必须在排除名单内。

    /** 原始写 keystore blob 到 `0:/.midun_keystore`（不经 DEK 加密）。需盘已打开。 */
    fun saveKeystoreRaw(bytes: ByteArray): Boolean =
        writeFile(KEYSTORE_PATH, bytes.inputStream()).isSuccess

    /** 原始读 keystore blob；不存在（旧卡未初始化此格式）返回 null。需盘已打开。 */
    fun loadKeystoreRaw(): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (readFile(KEYSTORE_PATH, out).isSuccess) out.toByteArray() else null
    }

    /**
     * 把卡内 [path] 流式读出到 [output]，64KB 分块。返回读出字节数（加密文件=明文字节数）。
     *
     * **M12.3 解密分流**：用户文件且密钥库已解锁 → 读文件头，MAGIC 命中则逐块 DEK 解密写出明文；旧未加密
     * 用户文件 → 回卷原样读。元数据/缓存侧车（非用户文件路径）一律原始字节，不解密（聊天/日志/keystore/
     * 绑定的 JSON 本就不叠 DEK）。
     */
    fun readFile(path: String, output: OutputStream): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFOpen(path)
        if (handle <= 0) return Result.failure(IllegalStateException("打开文件失败"))
        try {
            val dek = cardKeystore.dek()
            if (isUserFilePath(path) && dek != null) {
                val head = ByteArray(FileHeader.BYTES)
                val parsed = if (readFullyFromCard(handle, head)) FileHeader.parse(head) else null
                if (parsed != null) return decryptTo(handle, parsed, dek, output)
                LibJniFSShell.SFSeek64(handle, 0, 0) // 旧未加密用户文件：回卷读原始字节
            }
            var total = 0L
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = LibJniFSShell.SFRead(handle, buf, 0, CHUNK)
                if (n < 0) return Result.failure(IllegalStateException("读取失败 @${total}"))
                if (n == 0) break
                output.write(buf, 0, n)
                total += n
            }
            Result.success(total)
        } finally {
            LibJniFSShell.SFClose(handle)
        }
    }

    /** 逐块 DEK 解密写出（M12.3）。句柄已越过文件头；按 [FileHeader] 的明文大小推每块密文长度。须持 fsShell 锁。 */
    private fun decryptTo(
        handle: Int, parsed: FileHeader.Parsed, dek: ByteArray, output: OutputStream
    ): Result<Long> {
        val size = parsed.plaintextSize
        val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        var total = 0L
        return try {
            for (index in 0 until totalChunks) {
                val plainLen = minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                val cipher = ByteArray(plainLen + FileCrypto.GCM_TAG_BYTES)
                if (!readFullyFromCard(handle, cipher)) throw IOException("密文不足 @chunk$index")
                val isLast = index == totalChunks - 1
                val plain = FileCrypto.decryptChunk(dek, parsed.fileNonce, index, isLast, cipher)
                output.write(plain)
                total += plain.size
            }
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // —— 内部工具 ——

    /**
     * 列目录项。[dirs]=true 取子目录、false 取文件。用官方 `GetFileList(path, ArrayList)` 包装（UTF-16LE +
     * 解析），项前缀 `0=`(目录)/`1=`(文件)；跳过 `.` 开头（含 `..` 与侧车 [META_PATH]）。
     */
    private fun listEntries(path: String, dirs: Boolean): List<String> {
        val raw = ArrayList<String>()
        synchronized(fsShell) { fsShell.GetFileList(path, raw) }
        val prefix = if (dirs) "0=" else "1="
        return raw.mapNotNull { entry ->
            if (!entry.startsWith(prefix)) return@mapNotNull null
            val name = entry.substring(2)
            if (name.isEmpty() || name.startsWith(".")) null else name
        }
    }

    /**
     * 读单文件大小（M12.2 大小记账）：`SFGetSize` 只给密文大小，加密用户文件的明文大小存在 [FileHeader] 里
     * → 读头解析。文件头缺失/MAGIC 不符（未加密的旧文件）回退原始大小。失败回 0。
     */
    private fun fileSize(path: String): Long = synchronized(fsShell) {
        val handle = LibJniFSShell.SFOpen(path)
        if (handle <= 0) return 0L
        try {
            val raw = LibJniFSShell.SFGetSize(handle)
            if (raw < FileHeader.BYTES) return if (raw >= 0) raw else 0L
            val head = ByteArray(FileHeader.BYTES)
            if (readFullyFromCard(handle, head)) {
                FileHeader.parse(head)?.let { return it.plaintextSize }
            }
            raw // 未加密旧文件
        } finally {
            LibJniFSShell.SFClose(handle)
        }
    }

    /** 从卡句柄精确读满 [buf]（SFRead 可能短读，0=EOF/<0=失败即停）。读满回 true。须在 fsShell 锁内调用。 */
    private fun readFullyFromCard(handle: Int, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = LibJniFSShell.SFRead(handle, buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    // —— 拷贝策略侧车（落卡）——

    private fun loadPolicies(): Map<String, CopyPolicy> {
        folderPolicies?.let { return it }
        val map = mutableMapOf<String, CopyPolicy>()
        runCatching {
            val sb = java.io.ByteArrayOutputStream()
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

    private fun setPolicy(folderPath: String, policy: CopyPolicy) {
        loadPolicies()
        folderPolicies!![folderPath] = policy
        savePolicies()
    }

    private fun removePolicy(folderPath: String) {
        loadPolicies()
        if (folderPolicies!!.remove(folderPath) != null) savePolicies()
    }

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

    private fun guessFileType(fileName: String): FileType =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp" -> FileType.IMAGE
            "mp4", "mkv", "avi", "mov" -> FileType.VIDEO
            "mp3", "aac", "wav", "m4a" -> FileType.AUDIO
            "pdf", "doc", "docx", "txt", "xls", "xlsx" -> FileType.DOCUMENT
            else -> FileType.OTHER
        }

    private fun fsError(msg: String, code: Int) = IllegalStateException("$msg，错误码=$code")

    private companion object {
        const val ROOT = "0:/"
        const val META_PATH = "0:/.midun_meta.json"
        const val KEYSTORE_PATH = "0:/.midun_keystore" // App 层 DEK/KEK 密钥库（raw，永不 DEK 加密）
        const val CHUNK = 64 * 1024
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 // 100MB 导入上限（需求）

    }
}
