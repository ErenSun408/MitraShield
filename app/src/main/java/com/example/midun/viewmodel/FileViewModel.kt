package com.example.midun.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.crypto.FileContainer
import com.example.midun.data.FileRepository
import com.example.midun.data.TransferCancelledException
import com.example.midun.data.OperationLogRepository
import com.example.midun.data.SettingsStore
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileSort
import com.example.midun.data.model.FileType
import com.example.midun.data.model.OperationType
import com.example.midun.data.real.RealFileSystem
import com.example.midun.media.CardMediaDataSource
import com.example.midun.util.decodeSampledBitmap
import com.example.midun.util.scaleDownBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FileUiState(
    val folders: List<FileItem> = emptyList(),
    val totalFileCount: Int = 0,
    val currentFolderId: String? = null,
    val currentFiles: List<FileItem> = emptyList(),
    val isLoading: Boolean = false
)

/** 单文件字节进度（导入 M11.5.1 / 单文件导出 M-files 共用）。`total<=0` 表示大小未知，进度不确定。 */
data class FileByteProgress(val fileName: String, val written: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** 单文件导出结果（弹窗提示）。 */
data class ExportResult(val success: Boolean, val message: String)

/**
 * 文件夹导出进度（M11.5.6 + M-files 增当前文件字节进度）。[done]/[total] 为文件个数计数；[currentFile]/
 * [fileWritten]/[fileTotal] 为当前正在导出文件的字节进度。[finished] 后 UI 显示 [message] 结果、可关闭。
 */
data class ExportProgress(
    val done: Int,
    val total: Int,
    val finished: Boolean = false,
    val message: String = "",
    val currentFile: String = "",
    val fileWritten: Long = 0,
    val fileTotal: Long = 0
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val fileFraction: Float get() = if (fileTotal > 0) (fileWritten.toFloat() / fileTotal).coerceIn(0f, 1f) else 0f
}

@HiltViewModel
class FileViewModel @Inject constructor(
    private val fileSystem: FileRepository,
    // 视频首帧提取需卡内随机读解密（经 CardMediaDataSource），故直接依赖 RealFileSystem（同 PreviewViewModel）。
    private val realFileSystem: RealFileSystem,
    private val operationLog: OperationLogRepository,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * 列表排序方式，**文件夹与文件各一份**（分开的理由见 [SettingsStore.folderSort]）。转发 DataStore 那两条流，
     * 故本 VM 有几个实例都不影响，且跨重启记住。
     *
     * Eagerly：进屏时列表往往已经在了，晚一拍再排会看见一次明显的重排跳动。
     */
    val folderSort: StateFlow<FileSort> = settingsStore.folderSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, FileSort.FOLDER_DEFAULT)

    val fileSort: StateFlow<FileSort> = settingsStore.fileSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, FileSort.FILE_DEFAULT)

    fun setFolderSort(sort: FileSort) {
        viewModelScope.launch { settingsStore.setFolderSort(sort) }
    }

    fun setFileSort(sort: FileSort) {
        viewModelScope.launch { settingsStore.setFileSort(sort) }
    }

    sealed class OperationResult {
        data class Success(val message: String) : OperationResult()
        data class Error(val message: String) : OperationResult()
    }

    private val _uiState = MutableStateFlow(FileUiState())
    val uiState: StateFlow<FileUiState> = _uiState.asStateFlow()

    private val _operationResult = MutableSharedFlow<OperationResult>()
    val operationResult: SharedFlow<OperationResult> = _operationResult.asSharedFlow()

    /** 非空表示导入进行中，供 UI 显示进度对话框；导入结束（成功/失败）置空。 */
    private val _importProgress = MutableStateFlow<FileByteProgress?>(null)
    val importProgress: StateFlow<FileByteProgress?> = _importProgress.asStateFlow()

    /** 选中的是 `.midun` 容器（按魔数识别）→ 非空时 UI 弹口令框收口令、确认后调 [importContainer]（M12.5 解密侧）。 */
    data class PendingContainer(val uri: Uri, val fileName: String)
    private val _pendingContainer = MutableStateFlow<PendingContainer?>(null)
    val pendingContainer: StateFlow<PendingContainer?> = _pendingContainer.asStateFlow()
    fun clearPendingContainer() { _pendingContainer.value = null }

    /** 非空表示单文件导出进行中，供 UI 显示字节进度对话框；结束（成功/失败）置空。 */
    private val _fileExportProgress = MutableStateFlow<FileByteProgress?>(null)
    val fileExportProgress: StateFlow<FileByteProgress?> = _fileExportProgress.asStateFlow()

    /** 非空表示文件夹导出进行中/已完成（finished=true 显示结果）；由 [clearExportProgress] 关闭。 */
    private val _exportProgress = MutableStateFlow<ExportProgress?>(null)
    val exportProgress: StateFlow<ExportProgress?> = _exportProgress.asStateFlow()

    /** 单文件导出结果，非空时 UI 弹窗提示；由 [clearFileExportResult] 关闭。 */
    private val _fileExportResult = MutableStateFlow<ExportResult?>(null)
    val fileExportResult: StateFlow<ExportResult?> = _fileExportResult.asStateFlow()

    /**
     * 用户取消标志（M-files Stage3）。底层读写循环每块查 `isCancelled` 回调，命中即以
     * [TransferCancelledException] 收尾、清半成品。同一时刻 UI 只有一个传输弹窗（导入/单导出/文件夹导出互斥），
     * 故共用一个标志即可。每次传输开始前由各入口重置为 false。`@Volatile`：传输跑在 IO 线程，取消由主线程置位。
     */
    @Volatile private var transferCancelled = false

    /** 取消进行中的导入/导出（进度弹窗「取消」按钮）。仅置标志，传输协程下一块自然收尾并清理。 */
    fun cancelTransfer() {
        transferCancelled = true
    }

    init {
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            val folders = fileSystem.getFolders()
            val total = fileSystem.getTotalFileCount()
            _uiState.update { it.copy(folders = folders, totalFileCount = total) }
        }
    }

    fun loadFiles(folderId: String) {
        viewModelScope.launch {
            val files = fileSystem.getFilesInFolder(folderId)
            _uiState.update { it.copy(currentFolderId = folderId, currentFiles = files) }
        }
    }

    /**
     * 图片缩略图缓存（隐私文件夹列表用）：真卡每张图都要 SFOpen+解密+解码，代价高，用 LruCache 兜住
     * 列表滚动/重组的重复加载。按条数上限，缩略图小、内存可控。key = fileId。
     */
    private val thumbnailCache = LruCache<String, ImageBitmap>(THUMB_CACHE_ENTRIES)

    /** 视频首帧提取串行锁：真卡随机读慢、MediaMetadataRetriever 重，串行避免多个并发压垮卡/SDK。 */
    private val videoThumbMutex = Mutex()

    /**
     * 读文件 [file] 的缩略图（客户反馈：列表原来只有通用图标，看不出内容）。命中缓存直接返回；图片走内存
     * 解密+下采样，视频走首帧提取。读/解码失败回 null（UI 退回通用图标）。
     */
    suspend fun loadThumbnail(file: FileItem): ImageBitmap? {
        thumbnailCache.get(file.id)?.let { return it }
        val bmp = when (file.type) {
            FileType.IMAGE -> {
                val bytes = fileSystem.readFileBytes(file.id).getOrNull() ?: return null
                withContext(Dispatchers.Default) { decodeSampledBitmap(bytes, THUMB_MAX_PX)?.asImageBitmap() }
            }
            FileType.VIDEO -> loadVideoFrame(file.id)
            else -> null
        } ?: return null
        thumbnailCache.put(file.id, bmp)
        return bmp
    }

    /**
     * 提取视频 [fileId] 的首帧作缩略图：自定义 [CardMediaDataSource] 喂 MediaMetadataRetriever，卡内随机读
     * 解密、**不落盘**。取不到帧（异常编码/不支持等）回 null → UI 退回通用视频图标。
     */
    private suspend fun loadVideoFrame(fileId: String): ImageBitmap? = videoThumbMutex.withLock {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val source = CardMediaDataSource(realFileSystem, fileId)
            try {
                retriever.setDataSource(source)
                val frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                frame?.let { scaleDownBitmap(it, THUMB_MAX_PX).asImageBitmap() }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
                runCatching { source.close() }
            }
        }
    }

    fun createFolder(name: String, policy: CopyPolicy) {
        viewModelScope.launch {
            setLoading(true)
            fileSystem.createFolder(name, policy)
                .onSuccess {
                    loadFolders()
                    operationLog.record(OperationType.FOLDER_CREATE, "新建文件夹「$name」")
                    _operationResult.emit(OperationResult.Success("文件夹创建成功"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "创建失败"))
                }
            setLoading(false)
        }
    }

    /**
     * 从系统文件选取器（`GetContent`）返回的 [uri] 真实流式导入到 [folderId]（M11.5.1）。
     * 经 `OpenableColumns` 取文件名/大小，先做 100MB 上限校验，再交 [fileSystem] 64KB 分块写入并回报进度。
     */
    fun importFromUri(folderId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = queryMeta(uri)
            if (meta == null) {
                _operationResult.emit(OperationResult.Error("无法读取所选文件"))
                return@launch
            }
            val (name, size) = meta
            // 识别口令保护的 .midun 容器（按内容魔数，改名也认）→ 转「输口令解密」流程，不当普通文件导入。
            if (isMidunContainer(uri)) {
                _pendingContainer.value = PendingContainer(uri, name)
                return@launch
            }
            if (size > MAX_IMPORT_BYTES) {
                _operationResult.emit(OperationResult.Error("文件超过 100MB 上限，无法导入"))
                return@launch
            }
            transferCancelled = false
            _importProgress.value = FileByteProgress(name, 0L, size)
            fileSystem.importFile(
                folderId = folderId,
                fileName = name,
                size = size,
                openStream = {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("打开文件失败")
                },
                isCancelled = { transferCancelled },
                onProgress = { written -> _importProgress.value = FileByteProgress(name, written, size) }
            ).onSuccess {
                loadFiles(folderId)
                operationLog.record(OperationType.FILE_IMPORT, "导入「$name」")
                _operationResult.emit(OperationResult.Success("文件导入成功"))
            }.onFailure {
                // 取消：半成品已由 RealFileSystem.importFile 删除，仅提示「已取消」（非错误）。
                if (it is TransferCancelledException) _operationResult.emit(OperationResult.Success("已取消导入"))
                else _operationResult.emit(OperationResult.Error(it.message ?: "导入失败"))
            }
            _importProgress.value = null
        }
    }

    /** peek [uri] 首字节判断是否为 `.midun` 容器（M12.5 解密侧）。读不到/非容器回 false。 */
    private fun isMidunContainer(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(FileContainer.MAGIC.size)
            var off = 0
            while (off < head.size) {
                val n = input.read(head, off, head.size - off); if (n < 0) break; off += n
            }
            off == head.size && FileContainer.isContainer(head)
        } ?: false
    }.getOrDefault(false)

    /**
     * 导入一个口令保护的 `.midun` 容器（M12.5 解密侧补齐：原本只做了导出、没接「解回来」）：用导出口令把容器
     * 解回原文件、落进隐私文件夹 [folderId]。容器自包含（salt+迭代次数都在头里）→ **跨设备只要口令对就能解**，
     * 与卡内 DEK 无关。先解密到 App 临时文件（拿到原名/大小），再按原名走正常导入（落卡时再被卡 DEK 静态加密、
     * 此后是普通可预览文件）。口令错 → [FileContainer.BadPassphraseException] → 提示「口令错误」。
     */
    fun importContainer(folderId: String, uri: Uri, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            transferCancelled = false
            val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.tmp")
            _importProgress.value = FileByteProgress("解密中…", 0L, 0L)
            try {
                val meta = (context.contentResolver.openInputStream(uri)
                    ?: throw IOException("打开文件失败")).use { input ->
                    tmp.outputStream().use { out ->
                        FileContainer.decrypt(
                            input, passphrase, out,
                            isCancelled = { transferCancelled },
                            onProgress = { w -> _importProgress.value = FileByteProgress("解密中…", w, 0L) }
                        )
                    }
                }
                if (tmp.length() > MAX_IMPORT_BYTES) throw IOException("解密后文件超过 100MB 上限")
                _importProgress.value = FileByteProgress(meta.originalName, 0L, tmp.length())
                fileSystem.importFile(
                    folderId = folderId,
                    fileName = meta.originalName,
                    size = tmp.length(),
                    openStream = { tmp.inputStream() },
                    isCancelled = { transferCancelled },
                    onProgress = { w -> _importProgress.value = FileByteProgress(meta.originalName, w, tmp.length()) }
                ).getOrThrow()
                loadFiles(folderId)
                operationLog.record(OperationType.FILE_IMPORT, "导入「${meta.originalName}」")
                _operationResult.emit(OperationResult.Success("已解密导入「${meta.originalName}」"))
            } catch (e: Exception) {
                when (e) {
                    is TransferCancelledException -> _operationResult.emit(OperationResult.Success("已取消导入"))
                    is FileContainer.BadPassphraseException ->
                        _operationResult.emit(OperationResult.Error("口令错误或文件已损坏"))
                    else -> _operationResult.emit(OperationResult.Error(e.message ?: "解密导入失败"))
                }
            } finally {
                tmp.delete()
                _importProgress.value = null
            }
        }
    }

    /** 经 ContentResolver 查 [uri] 的显示名与大小；名缺失视为不可用。大小未知回 0（由下游兜底）。 */
    private fun queryMeta(uri: Uri): Pair<String, Long>? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
            if (name.isNullOrBlank()) null else name to size
        }

    /**
     * 把文件 [fileId]（当前在 [fromFolderId]）移动到隐私文件夹 [targetFolderId]。成功后刷新当前文件夹列表
     * 并记一条操作日志；目标已存在同名文件/目标即当前文件夹等情形由下游失败返回、走 Snackbar 提示。
     */
    fun moveFile(fileId: String, fileName: String, fromFolderId: String, targetFolderId: String) {
        viewModelScope.launch {
            fileSystem.moveFile(fileId, targetFolderId)
                .onSuccess {
                    loadFiles(fromFolderId)
                    operationLog.record(OperationType.FILE_MOVE, "移动「$fileName」")
                    _operationResult.emit(OperationResult.Success("文件已移动"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "移动失败"))
                }
        }
    }

    fun deleteFile(fileId: String, folderId: String) {
        val fileName = _uiState.value.currentFiles.find { it.id == fileId }?.name ?: "文件"
        viewModelScope.launch {
            fileSystem.deleteFile(fileId)
                .onSuccess {
                    loadFiles(folderId)
                    operationLog.record(OperationType.FILE_DELETE, "删除「$fileName」")
                    _operationResult.emit(OperationResult.Success("文件已删除"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "删除失败"))
                }
        }
    }

    fun deleteAllFilesInFolder(folderId: String) {
        val folderName = _uiState.value.folders.find { it.id == folderId }?.name
        viewModelScope.launch {
            fileSystem.deleteAllFilesInFolder(folderId)
                .onSuccess {
                    loadFiles(folderId)
                    operationLog.record(
                        OperationType.FILE_DELETE,
                        if (folderName != null) "清空「$folderName」内全部文件" else "清空文件夹内全部文件"
                    )
                    _operationResult.emit(OperationResult.Success("文件夹内文件已全部删除"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "删除失败"))
                }
        }
    }

    fun deleteFolder(folderId: String) {
        val folderName = _uiState.value.folders.find { it.id == folderId }?.name ?: "文件夹"
        viewModelScope.launch {
            fileSystem.deleteFolder(folderId)
                .onSuccess {
                    loadFolders()
                    if (_uiState.value.currentFolderId == folderId) {
                        _uiState.update { it.copy(currentFolderId = null, currentFiles = emptyList()) }
                    }
                    operationLog.record(OperationType.FILE_DELETE, "删除文件夹「$folderName」")
                    _operationResult.emit(OperationResult.Success("文件夹已删除"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "删除失败"))
                }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        val oldName = _uiState.value.folders.find { it.id == folderId }?.name ?: "文件夹"
        viewModelScope.launch {
            val trimmedName = newName.trim()
            if (trimmedName.isBlank()) {
                _operationResult.emit(OperationResult.Error("文件夹名称不能为空"))
                return@launch
            }

            fileSystem.renameFolder(folderId, trimmedName)
                .onSuccess {
                    loadFolders()
                    operationLog.record(OperationType.FILE_RENAME, "重命名文件夹「$oldName」→「$trimmedName」")
                    _operationResult.emit(OperationResult.Success("文件夹已重命名"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "重命名失败"))
                }
        }
    }

    /**
     * 重命名文件。[newType] 非空表示用户在弹框里**确认过**「后缀与实际类型不匹配，仍要改」，此时顺带把
     * 文件头里的类型码改成按新后缀判出来的那个，图标才跟得上（见 `FileSystemOps.setFileType`）。
     *
     * 改类型排在改名**之后**：改名一步就是 `SFRename`，几乎不会失败；类型改写要开文件、写头、再读回来核对，
     * 失败面大得多。反过来先改类型的话，一旦改名失败，用户就得到一个「名字没变、图标却变了」的四不像。
     * 类型改写失败也不回滚改名——名字是用户明确要的，类型只是跟着走的那一半，如实提示即可。
     */
    fun renameFile(fileId: String, newName: String, folderId: String, newType: FileType? = null) {
        val oldName = _uiState.value.currentFiles.find { it.id == fileId }?.name
        viewModelScope.launch {
            val trimmedName = newName.trim()
            if (trimmedName.isBlank()) {
                _operationResult.emit(OperationResult.Error("文件名称不能为空"))
                return@launch
            }

            fileSystem.renameFile(fileId, trimmedName)
                .onSuccess {
                    // 改名后路径变了，类型要写到**新**路径上。
                    val typeError = newType?.let { t ->
                        // 改名后路径变了，类型写到新路径上。
                        fileSystem.setFileType(fileId.substringBeforeLast('/') + "/" + trimmedName, t)
                            .exceptionOrNull()
                    }
                    loadFiles(folderId)
                    operationLog.record(
                        OperationType.FILE_RENAME,
                        if (oldName != null) "重命名文件「$oldName」→「$trimmedName」" else "重命名文件为「$trimmedName」"
                    )
                    // 一次操作只报一句：类型没跟上时报那句更重要的，别叠两条 Snackbar。
                    _operationResult.emit(
                        if (typeError == null) {
                            OperationResult.Success("文件已重命名")
                        } else {
                            OperationResult.Error("已重命名，但文件类型未能更新：${typeError.message ?: "未知原因"}")
                        }
                    )
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "重命名失败"))
                }
        }
    }

    /**
     * 把卡内文件 [fileId] 真实导出到系统选取器（`CreateDocument`）返回的 [uri]（M11.5.2）。读隐藏区明文流式写出。
     * 导出受 UI 拷贝策略门控（NO_COPY 不可见）。
     *
     * [passphrase] 非空（拷贝密文策略，M12.5）→ 用该导出口令把文件重加密成便携 `.midun` 容器写出；为空 → 明文导出。
     */
    fun exportFileToUri(fileId: String, fileName: String, size: Long, uri: Uri, passphrase: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            transferCancelled = false
            _fileExportProgress.value = FileByteProgress(fileName, 0L, size)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    val onProg: (Long) -> Unit = { _fileExportProgress.value = FileByteProgress(fileName, it, size) }
                    if (passphrase != null)
                        fileSystem.exportFileEncrypted(fileId, fileName, out, passphrase, { transferCancelled }, onProg).getOrThrow()
                    else
                        fileSystem.exportFile(fileId, fileName, out, { transferCancelled }, onProg).getOrThrow()
                } ?: throw IOException("无法写入目标位置")
            }.onSuccess {
                // 日志文案不提「加密」（客户 2026-07-28）：加不加密导出记同一句。
                operationLog.record(OperationType.FILE_EXPORT, "导出「$fileName」")
                _fileExportResult.value = ExportResult(
                    true, if (passphrase != null) "已加密导出「$fileName.${FileContainer.EXTENSION}」到所选位置" else "已导出「$fileName」到所选位置"
                )
            }.onFailure {
                if (it is TransferCancelledException) {
                    // 取消：删半成品 SAF 文档（系统选取器已先建空文档），提示「已取消」。
                    runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
                    _fileExportResult.value = ExportResult(false, "已取消导出")
                } else {
                    _fileExportResult.value = ExportResult(false, it.message ?: "导出失败")
                }
            }
            _fileExportProgress.value = null
        }
    }

    fun clearFileExportResult() {
        _fileExportResult.value = null
    }

    /**
     * 把整个文件夹导出到用户经 `OpenDocumentTree` 选定的目录 [treeUri]（M11.5.6）：在该目录下建一个同名
     * 子目录，再把文件夹内下一级所有文件逐个流式写入。按文件个数回报进度。
     *
     * [passphrase] 非空（拷贝密文策略，M12.5）→ 每个文件用同一导出口令重加密成各自独立的 `.midun` 容器
     * （各自 salt/nonce，可单独开封）；为空 → 明文原样导出。
     */
    fun exportFolderToTree(folderId: String, folderName: String, treeUri: Uri, passphrase: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
            if (tree == null || !tree.canWrite()) {
                _operationResult.emit(OperationResult.Error("无法写入所选目录"))
                return@launch
            }
            val files = fileSystem.getFilesInFolder(folderId)
            if (files.isEmpty()) {
                _operationResult.emit(OperationResult.Error("文件夹为空，无文件可导出"))
                return@launch
            }
            // 在选定目录下建同名子目录；失败（如同名已存在受限）则退回直接写选定目录。
            val dir = tree.createDirectory(folderName) ?: tree
            transferCancelled = false
            _exportProgress.value = ExportProgress(0, files.size)
            var ok = 0
            var cancelled = false
            for ((i, f) in files.withIndex()) {
                if (transferCancelled) { cancelled = true; break }
                _exportProgress.value = ExportProgress(i, files.size, currentFile = f.name, fileTotal = f.size)
                // 加密导出落 <名>.midun；明文导出保留原名。先建目标文档，留引用以便取消时删半成品。
                val targetName = if (passphrase != null) "${f.name}.${FileContainer.EXTENSION}" else f.name
                val target = dir.createFile("application/octet-stream", targetName)
                runCatching {
                    (target ?: throw IOException("创建文件失败：$targetName"))
                    context.contentResolver.openOutputStream(target.uri)?.use { out ->
                        val onProg: (Long) -> Unit = {
                            _exportProgress.value = ExportProgress(
                                i, files.size, currentFile = f.name, fileWritten = it, fileTotal = f.size
                            )
                        }
                        if (passphrase != null)
                            fileSystem.exportFileEncrypted(f.id, f.name, out, passphrase, { transferCancelled }, onProg).getOrThrow()
                        else
                            fileSystem.exportFile(f.id, f.name, out, { transferCancelled }, onProg).getOrThrow()
                    } ?: throw IOException("打开输出失败：${f.name}")
                }.onSuccess { ok++ }
                    .onFailure {
                        if (it is TransferCancelledException) {
                            runCatching { target?.delete() } // 删当前半成品
                            cancelled = true
                        }
                    }
                if (cancelled) break
                _exportProgress.value = ExportProgress(i + 1, files.size, currentFile = f.name, fileTotal = f.size)
            }
            val verb = if (passphrase != null) "加密导出" else "导出"
            val summary =
                if (cancelled) "已取消，已$verb $ok/${files.size} 个文件到「$folderName」"
                else "已$verb $ok/${files.size} 个文件到「$folderName」"
            // 日志文案不提「加密」：弹框 summary 仍用 verb，日志固定说「导出」。
            operationLog.record(
                OperationType.FILE_EXPORT,
                "导出文件夹「$folderName」（$ok/${files.size} 个文件）"
            )
            _exportProgress.value = ExportProgress(
                files.size, files.size, finished = true, message = summary
            )
        }
    }

    /** 关闭文件夹导出进度/结果对话框。 */
    fun clearExportProgress() {
        _exportProgress.value = null
    }

    /** 记录一次导出操作（单文件已走 [exportFileToUri]、文件夹走 [exportFolderToTree] 真实导出；仅备用）。 */
    fun recordExport(description: String) {
        operationLog.record(OperationType.FILE_EXPORT, description)
    }

    private fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 // 100MB（需求上限，与 RealFileSystem 一致）
        const val THUMB_MAX_PX = 256      // 缩略图最长边像素（列表 40dp 项足够清晰）
        const val THUMB_CACHE_ENTRIES = 48 // 缩略图缓存条数上限（256px 小图，内存可控）
    }
}
