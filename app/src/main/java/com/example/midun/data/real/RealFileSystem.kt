package com.example.midun.data.real

import com.example.midun.data.FileSystemOps
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import java.io.ByteArrayOutputStream
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
class RealFileSystem @Inject constructor() : FileSystemOps {

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
        val result = openStream().use { input -> writeFile(path, input, MAX_IMPORT_BYTES, onProgress) }
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
        // fileId 即隐藏区完整路径；readFile 读出解密后明文（卡内加密存储 → SFRead 已解密）。
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
     */
    fun writeFile(
        path: String,
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (Long) -> Unit = {}
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

    /** 把卡内 [path] 流式读出到 [output]，64KB 分块。返回读出字节数。 */
    fun readFile(path: String, output: OutputStream): Result<Long> = synchronized(fsShell) {
        val handle = LibJniFSShell.SFOpen(path)
        if (handle <= 0) return Result.failure(IllegalStateException("打开文件失败"))
        var total = 0L
        try {
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

    /** `SFOpen` → `SFGetSize` → `SFClose` 读单文件大小；失败回 0。 */
    private fun fileSize(path: String): Long = synchronized(fsShell) {
        val handle = LibJniFSShell.SFOpen(path)
        if (handle <= 0) return 0L
        try {
            LibJniFSShell.SFGetSize(handle)
        } finally {
            LibJniFSShell.SFClose(handle)
        }
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
        const val CHUNK = 64 * 1024
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 // 100MB 导入上限（需求）

    }
}
