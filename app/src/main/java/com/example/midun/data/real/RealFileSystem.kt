package com.example.midun.data.real

import com.example.midun.crypto.FileContainer
import com.example.midun.crypto.FileCrypto
import com.example.midun.crypto.FileHeader
import com.example.midun.data.FileSystemOps
import com.example.midun.data.FileTypes
import com.example.midun.data.TransferCancelledException
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

    // 懒加载：getLibFSShellInstance() 会 System.loadLibrary native 库。@Singleton 在启动构建依赖图时即被造出,
    // 饿汉初始化会把 loadLibrary 压到主线程 onCreate → 拉长启动白屏。改 by lazy 推迟到首次卡操作(IO 线程)。
    private val fsShell: LibJniFSShell by lazy { FSShellInstance.getLibFSShellInstance() }

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
                // 文件夹暂不读卡上创建时间（沿用 FileItem 默认值 = 此刻，即显示「今天」）。读它必须先用
                // `SFOpenAsDir` 拿目录句柄——`SFGetTime` 只认句柄，而列目录走的是按路径的 GetFileList、
                // 不产生句柄。`SFOpenAsDir` 是本 App 从未调用过、SDK 示例里也零覆盖的函数，恰好又是 2026-07-29
                // 唯一新增的 native 调用面，与客户「发图片时会话断开」的起始版本重合，故先撤出、留作断连排查的
                // 干净实验条件（`[files]` 2026-07-30）。断连定案后若仍要文件夹日期，再单独试它并真机验证。
                copyPolicy = policies[path] ?: CopyPolicy.NO_COPY
            )
        }
    }

    override suspend fun getFilesInFolder(folderId: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            listEntries("$folderId/", dirs = false).map { name ->
                val path = "$folderId/$name"
                val meta = fileMeta(path) // 一次打开拿明文大小 + 类型（M-files）+ 卡上创建时间
                FileItem(
                    id = path,
                    name = name,
                    type = meta.type,
                    size = meta.size,
                    createdAt = meta.createdAt,
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
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val path = "$folderId/$fileName"
        // M11.5.1：真实字节流式导入——64KB 分块写入隐藏区，100MB 上限兜底。
        // M12.2：用户文件路径 → writeFile 用 DEK 加密落卡（需明文大小写文件头，传 plaintextSize）。
        val result = openStream().use { input ->
            writeFile(path, input, MAX_IMPORT_BYTES, plaintextSize = size, isCancelled = isCancelled, onProgress = onProgress)
        }
        if (result.isFailure) synchronized(fsShell) { LibJniFSShell.SFDelete(path) } // 删半成品（含取消）
        // 返回值类型仅占位（UI 成功后重载文件夹，权威类型来自落卡文件头）；扩展名粗判即可。
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
        // fileId 即隐藏区完整路径；readFile 对加密用户文件逐块 DEK 解密写出明文（M12.3）。导出==原文。
        readFile(fileId, output, isCancelled, onProgress)
    }

    override suspend fun readFileBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        readFile(fileId, out).map { out.toByteArray() }
    }

    /**
     * 加密导出（M12.5）：读卡内**明文**（[openCardStream] 解 DEK）+ 文件头（明文大小/类型），用导出口令经
     * [FileContainer] 重加密成 `.midun` 容器写到 [output]。返回明文字节数（进度总量）。容器脱离卡与 DEK，靠口令
     * 在别处也能解。[isCancelled] 每块查；**不关闭 [output]**（调用方负责）。
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
            val (size, type) = fileMeta(fileId) // 明文大小 + 内容判定类型（写进容器头，开封后列表/预览可用）
            openCardStream(fileId).use { plain ->
                FileContainer.encrypt(plain, size, fileName, type, passphrase, output, isCancelled, onProgress)
            }
            size
        }
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
            private val reader = BufferedCardReader(handle, IO_CALL) // 大块读卡，逐块解密
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
                    if (!synchronized(fsShell) { reader.readExact(cipher) }) {
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
            val meta = fileMeta(newPath) // 移动是密文整体搬运，文件头随之带走 → 读头得正确大小/类型
            Result.success(
                FileItem(
                    id = newPath,
                    name = fileName,
                    type = meta.type,
                    size = meta.size,
                    createdAt = meta.createdAt,
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
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ): Result<Long> {
        val dek = cardKeystore.dek()
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

    /** 原始字节写入（元数据/缓存侧车、未解锁旧卡）：64KB 分块直写，不加密。 */
    private fun writeRaw(
        path: String, input: InputStream, maxBytes: Long, isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFCreate(path)
        if (handle <= 0) return Result.failure(fsError("创建文件失败", handle))
        var total = 0L
        try {
            val buf = ByteArray(CHUNK)
            while (true) {
                if (isCancelled()) return Result.failure(TransferCancelledException()) // 半成品由调用方删
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
        path: String, input: InputStream, size: Long, dek: ByteArray,
        isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFCreate(path)
        if (handle <= 0) return Result.failure(fsError("创建文件失败", handle))
        try {
            val fileNonce = FileCrypto.newFileNonce()
            val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
            // 攒批写卡：加密块先进缓冲，满 IO_CALL 再一次性下发，把 SFWrite 次数从「每 16KB 一次」降到「每 64KB 一次」。
            val block = ByteArray(IO_CALL + CHUNK + FileCrypto.GCM_TAG_BYTES)
            var blockLen = 0
            fun appendToBlock(bytes: ByteArray) {
                System.arraycopy(bytes, 0, block, blockLen, bytes.size)
                blockLen += bytes.size
                if (blockLen >= IO_CALL) { writeFullyToCard(handle, block, 0, blockLen); blockLen = 0 }
            }
            // 先读首块明文，按内容（magic number）判型——无视文件名 → 对 movie.mp4(3) 等免疫；类型写进文件头。
            val firstWant = minOf(CHUNK.toLong(), size).toInt().coerceAtLeast(0)
            val firstPlain = ByteArray(firstWant)
            if (firstWant > 0) readFully(input, firstPlain)
            val fileType = FileTypes.detect(firstPlain, path.substringAfterLast('/'))
            appendToBlock(FileHeader.build(size, fileNonce, fileType))
            var done = 0L
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException() // 经下方 catch → 失败返回；半成品由调用方删
                val plain = if (index == 0) firstPlain else {
                    val want = minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt().coerceAtLeast(0)
                    ByteArray(want).also { if (want > 0) readFully(input, it) }
                }
                val isLast = index == totalChunks - 1
                appendToBlock(FileCrypto.encryptChunk(dek, fileNonce, index, isLast, plain))
                done += plain.size
                onProgress(done)
            }
            if (blockLen > 0) writeFullyToCard(handle, block, 0, blockLen) // 冲刷尾块
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

    /** 把 [bytes] 的 [off,off+len) 全量写入卡句柄；每次 SFWrite 不超过 [IO_CALL]（沿用真机验证的单调用大小，
     * 避免给原生传超大 len）。SFWrite 可能短写故循环。失败/无进展抛 [IOException]。须在 fsShell 锁内调用。 */
    private fun writeFullyToCard(handle: Int, bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        var p = off
        val end = off + len
        while (p < end) {
            val w = LibJniFSShell.SFWrite(handle, bytes, p, minOf(end - p, IO_CALL))
            if (w <= 0) throw IOException("写入卡失败/无进展 @$p/$end")
            p += w
        }
    }

    /**
     * 缓冲读卡（M12.3 优化）：按 [IO_CALL] 大块 SFRead 进内部缓冲，逐次取出小加密块——把 SFRead 次数从
     * 「每 16KB 一次」降到「每 64KB 一次」，导出/发送等顺序读不因小分块而变慢。每个实例独占一个卡句柄、
     * 顺序消费；[readExact] 须在 fsShell 锁内调用。
     */
    private class BufferedCardReader(private val handle: Int, capacity: Int) {
        private val buf = ByteArray(capacity)
        private var pos = 0
        private var lim = 0

        /** 从卡精确取满 [out]；卡内剩余不足返回 false。 */
        fun readExact(out: ByteArray): Boolean {
            var o = 0
            while (o < out.size) {
                if (pos >= lim) {
                    val n = LibJniFSShell.SFRead(handle, buf, 0, buf.size)
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

    // —— App 层密钥库 raw I/O（M12.1）——
    // keystore 存 DEK/KEK，必须以**原始字节**落卡/读卡：它本身就是「解密用户文件的密钥」，绝不能再被 DEK
    // 加密（鸡生蛋）。M12.2 给 writeFile/readFile 加 DEK 透明加解密时，[KEYSTORE_PATH] 必须在排除名单内。

    /** 原始写 keystore blob 到 `0:/.midun_keystore`（不经 DEK 加密）。需盘已打开。 */
    fun saveKeystoreRaw(bytes: ByteArray): Boolean =
        writeFile(KEYSTORE_PATH, bytes.inputStream()).isSuccess

    /**
     * 密钥更新（M12.6）：**安全覆盖** keystore。不裸截断写在原文件上——若写到一半中断会把 keystore 写坏，
     * DEK 随之不可解 = 隐私文件夹所有文件报废。改为「先写临时文件 → 旧文件改名备份 → 临时文件就位 → 删备份」：
     * 任一步崩溃后卡上都至少有一份完整 keystore（旧 [KEYSTORE_PATH] 或 [KEYSTORE_BAK]），由 [loadKeystoreRaw]
     * 自愈还原。新旧 blob 包裹**同一 DEK**，无论生效哪份都能解出相同密钥、文件不丢。需盘已打开。
     */
    fun rewriteKeystoreRaw(bytes: ByteArray): Boolean {
        synchronized(fsShell) {
            LibJniFSShell.SFDelete(KEYSTORE_TMP)
            LibJniFSShell.SFDelete(KEYSTORE_BAK)
        }
        if (!writeFile(KEYSTORE_TMP, bytes.inputStream()).isSuccess) {
            synchronized(fsShell) { LibJniFSShell.SFDelete(KEYSTORE_TMP) }
            return false
        }
        return synchronized(fsShell) {
            // 旧 keystore 先改名为备份（而非删）→ 即便下一步崩溃，备份仍是有效 keystore。
            if (!LibJniFSShell.SFRename(KEYSTORE_PATH, KEYSTORE_BAK)) {
                LibJniFSShell.SFDelete(KEYSTORE_TMP); return@synchronized false
            }
            if (!LibJniFSShell.SFRename(KEYSTORE_TMP, KEYSTORE_PATH)) {
                LibJniFSShell.SFRename(KEYSTORE_BAK, KEYSTORE_PATH) // 还原旧 keystore
                LibJniFSShell.SFDelete(KEYSTORE_TMP)
                return@synchronized false
            }
            LibJniFSShell.SFDelete(KEYSTORE_BAK)
            true
        }
    }

    /**
     * 原始读 keystore blob；不存在（旧卡未初始化此格式）返回 null。需盘已打开。
     * **自愈（M12.6）**：若主 keystore 缺失但 [KEYSTORE_BAK] 还在（密钥更新在两次 rename 之间中断），
     * 用备份并把它改名还原回主路径——备份包裹同一 DEK，等价于上次更新成功。
     */
    fun loadKeystoreRaw(): ByteArray? {
        readBytesOrNull(KEYSTORE_PATH)?.let { return it }
        val bak = readBytesOrNull(KEYSTORE_BAK) ?: return null
        synchronized(fsShell) { LibJniFSShell.SFRename(KEYSTORE_BAK, KEYSTORE_PATH) }
        return bak
    }

    private fun readBytesOrNull(path: String): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (readFile(path, out).isSuccess) out.toByteArray() else null
    }

    // —— 根级 JSON 侧车（操作日志 / 聊天记录）原子覆盖写 + 崩溃自愈读（防「直接拔卡」写坏）——
    // 旧路径 writeRaw 是原地 SFCreate 截断再写：拔卡卡在中途 → live 文件空/半截 → 下次登录解析失败，
    // 表现为「最近操作 / 聊天记录被清空」。沿用 keystore 的 tmp→bak→就位→删bak 策略，live 文件恒为
    // 完整旧版或完整新版，绝不出现空/半截。

    /** 原子覆盖写侧车 [path]（先写 .tmp → 旧文件改名 .bak → .tmp 就位 → 删 .bak）。首次写入无旧文件则直接就位。需盘已打开。 */
    fun atomicWriteSidecar(path: String, bytes: ByteArray): Boolean {
        val tmp = "$path.tmp"
        val bak = "$path.bak"
        synchronized(fsShell) {
            LibJniFSShell.SFDelete(tmp)
            LibJniFSShell.SFDelete(bak)
        }
        if (!writeFile(tmp, bytes.inputStream()).isSuccess) {
            synchronized(fsShell) { LibJniFSShell.SFDelete(tmp) }
            return false
        }
        return synchronized(fsShell) {
            val hadOld = exists(path)
            // 旧文件先改名为 .bak（而非删）→ 即便下一步崩溃，.bak 仍是完整旧版，供 readSidecarHealed 自愈。
            if (hadOld && !LibJniFSShell.SFRename(path, bak)) {
                LibJniFSShell.SFDelete(tmp); return@synchronized false
            }
            if (!LibJniFSShell.SFRename(tmp, path)) {
                if (hadOld) LibJniFSShell.SFRename(bak, path) // 还原旧版
                LibJniFSShell.SFDelete(tmp)
                return@synchronized false
            }
            LibJniFSShell.SFDelete(bak)
            true
        }
    }

    /** 读侧车 [path]；主文件缺失则回读 .bak 并改名还原（原子写卡在两次 rename 间被拔卡时的自愈）。都无返回 null。需盘已打开。 */
    fun readSidecarHealed(path: String): ByteArray? {
        readBytesOrNull(path)?.let { return it }
        val bak = readBytesOrNull("$path.bak") ?: return null
        synchronized(fsShell) { LibJniFSShell.SFRename("$path.bak", path) }
        return bak
    }

    /** 删侧车主文件及其原子写残留（.tmp/.bak）。退出清空 / 恢复出厂用，防残留 .bak 被 [readSidecarHealed] 复活已清数据。 */
    fun deleteSidecar(path: String) = synchronized(fsShell) {
        LibJniFSShell.SFDelete("$path.tmp")
        LibJniFSShell.SFDelete("$path.bak")
        LibJniFSShell.SFDelete(path)
    }

    /**
     * 把卡内 [path] 流式读出到 [output]，64KB 分块。返回读出字节数（加密文件=明文字节数）。
     *
     * **M12.3 解密分流**：用户文件且密钥库已解锁 → 读文件头，MAGIC 命中则逐块 DEK 解密写出明文；旧未加密
     * 用户文件 → 回卷原样读。元数据/缓存侧车（非用户文件路径）一律原始字节，不解密（聊天/日志/keystore/
     * 绑定的 JSON 本就不叠 DEK）。
     */
    fun readFile(
        path: String, output: OutputStream,
        isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}
    ): Result<Long> =
        synchronized(fsShell) {
            val handle = LibJniFSShell.SFOpen(path)
            if (handle <= 0) return Result.failure(IllegalStateException("打开文件失败"))
            try {
                val dek = cardKeystore.dek()
                if (isUserFilePath(path) && dek != null) {
                    val head = ByteArray(FileHeader.BYTES)
                    val parsed = if (readFullyFromCard(handle, head)) FileHeader.parse(head) else null
                    if (parsed != null) return decryptTo(handle, parsed, dek, output, isCancelled, onProgress)
                    LibJniFSShell.SFSeek64(handle, 0, 0) // 旧未加密用户文件：回卷读原始字节
                }
                var total = 0L
                val buf = ByteArray(CHUNK)
                while (true) {
                    if (isCancelled()) return Result.failure(TransferCancelledException()) // 半成品 SAF 文档由调用方删
                    val n = LibJniFSShell.SFRead(handle, buf, 0, CHUNK)
                    if (n < 0) return Result.failure(IllegalStateException("读取失败 @${total}"))
                    if (n == 0) break
                    output.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
                Result.success(total)
            } finally {
                LibJniFSShell.SFClose(handle)
            }
        }

    /** 逐块 DEK 解密写出（M12.3）。句柄已越过文件头；按 [FileHeader] 的明文大小推每块密文长度。须持 fsShell 锁。 */
    private fun decryptTo(
        handle: Int, parsed: FileHeader.Parsed, dek: ByteArray, output: OutputStream,
        isCancelled: () -> Boolean, onProgress: (Long) -> Unit
    ): Result<Long> {
        val size = parsed.plaintextSize
        val totalChunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        val reader = BufferedCardReader(handle, IO_CALL) // 大块读卡，逐块解密（少 SFRead 次数）
        var total = 0L
        return try {
            for (index in 0 until totalChunks) {
                if (isCancelled()) throw TransferCancelledException() // 经下方 catch → 失败返回
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
     * 读单文件的明文大小 + 类型（M12.2 大小记账 + M-files 类型）：一次读 [FileHeader] 同时拿到两者
     * （`SFGetSize` 只给密文大小、类型靠后缀易误判，故都存在头里）。文件头缺失/MAGIC 不符（未加密旧文件，
     * 重导后不应出现）→ 回退原始大小 + 扩展名判型。失败回 (0, OTHER)。
     */
    private fun fileMeta(path: String): Meta = synchronized(fsShell) {
        val handle = LibJniFSShell.SFOpen(path)
        if (handle <= 0) return Meta(0L, FileType.OTHER, 0L)
        try {
            val created = createTimeOf(handle)
            val raw = LibJniFSShell.SFGetSize(handle)
            if (raw >= FileHeader.BYTES) {
                val head = ByteArray(FileHeader.BYTES)
                if (readFullyFromCard(handle, head)) {
                    FileHeader.parse(head)?.let { return Meta(it.plaintextSize, it.fileType, created) }
                }
            }
            Meta(
                size = if (raw >= 0) raw else 0L,
                type = FileTypes.fromExtension(path.substringAfterLast('/')),
                createdAt = created
            )
        } finally {
            LibJniFSShell.SFClose(handle)
        }
    }

    /** [fileMeta] 的返回：明文大小 + 类型 + 卡上创建时间（0 = 未知）。 */
    private data class Meta(val size: Long, val type: FileType, val createdAt: Long)

    /**
     * 句柄 → 创建时间（毫秒，0 = 读不到）。须在 [fsShell] 锁内调用。
     *
     * 用 `SFGetTime(fd, long[3])`（本 jar 里是**静态**方法，同 SFOpen 那批）：返回 0 才算成功，数组依次是
     * 创建/修改/访问时间，**单位秒**——这不是猜的，SDK 自带示例 `FSDemoConsole.SFGetFileSizeAndCreateTime`
     * 就是这么用的，注释直接写着「精确到秒」；C 头亦为 `int SFGetTime(int fd, int64_t*, int64_t*, int64_t*)`。
     *
     * 原实现调的是 `SFGetFileCreateTime`（`[files]` 2026-07-30 修）：jar 里有 native 声明所以编译得过，但
     * **两个 ABI 的 `libjniFSShell.so` 里都没有这个符号**（`strings` 计数 0，导出的是 `SFGetTime`/`SFSetTime`），
     * 每次调用抛 UnsatisfiedLinkError 被 runCatching 吞掉、恒返回 0 —— 于是「显示卡上真实创建时间」这个功能
     * 从上线那天起就只显示 `--`，连带那套「按量级猜秒/毫秒/FILETIME」的启发式和它的原值采样诊断都是白做。
     */
    private fun createTimeOf(handle: Int): Long {
        val times = LongArray(3)
        val ret = runCatching { LibJniFSShell.SFGetTime(handle, times) }.getOrDefault(-1)
        return if (ret == 0 && times[0] > 0) times[0] * 1000L else 0L
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

    private fun fsError(msg: String, code: Int) = IllegalStateException("$msg，错误码=$code")

    private companion object {
        const val ROOT = "0:/"
        const val META_PATH = "0:/.midun_meta.json"
        const val KEYSTORE_PATH = "0:/.midun_keystore" // App 层 DEK/KEK 密钥库（raw，永不 DEK 加密）
        const val KEYSTORE_TMP = "0:/.midun_keystore.tmp" // 密钥更新临时文件（M12.6 安全覆盖）
        const val KEYSTORE_BAK = "0:/.midun_keystore.bak" // 密钥更新旧文件备份（M12.6 崩溃自愈）
        // 加密分块大小取 FileCrypto 的单一来源（消除「写/读两个 64KB 常量须保持一致」的隐患）；同时复用作
        // 原始拷贝/读写的 I/O 缓冲（缓冲大小非格式关键，取同值即可）。
        const val CHUNK = FileCrypto.CHUNK_PLAIN_BYTES
        // 卡 I/O 块大小（与加密块解耦）：单次 SFRead/SFWrite 上限 + 加密读写的攒批阈值。16KB 加密块若每块各
        // 调一次 SFRead/SFWrite，USB 来回次数会是 64KB 块的 4 倍 → 导入/导出变慢；攒够 64KB 再下发即恢复原速，
        // 而拖拽仍只读单个 16KB 块。实验：128KB（原 64KB，沿用 M11.5 已验证值）——测导入/导出是否更快。
        const val IO_CALL = 128 * 1024
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 // 100MB 导入上限（需求）

    }
}
