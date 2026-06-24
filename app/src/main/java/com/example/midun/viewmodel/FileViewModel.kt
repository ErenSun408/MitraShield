package com.example.midun.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.FileRepository
import com.example.midun.data.TransferCancelledException
import com.example.midun.data.mock.MockOperationLog
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.OperationType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val operationLog: MockOperationLog,
    @ApplicationContext private val context: Context
) : ViewModel() {

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

    fun createFolder(name: String, policy: CopyPolicy) {
        viewModelScope.launch {
            setLoading(true)
            fileSystem.createFolder(name, policy)
                .onSuccess {
                    loadFolders()
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
        viewModelScope.launch {
            val trimmedName = newName.trim()
            if (trimmedName.isBlank()) {
                _operationResult.emit(OperationResult.Error("文件夹名称不能为空"))
                return@launch
            }

            fileSystem.renameFolder(folderId, trimmedName)
                .onSuccess {
                    loadFolders()
                    _operationResult.emit(OperationResult.Success("文件夹已重命名"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "重命名失败"))
                }
        }
    }

    fun renameFile(fileId: String, newName: String, folderId: String) {
        viewModelScope.launch {
            val trimmedName = newName.trim()
            if (trimmedName.isBlank()) {
                _operationResult.emit(OperationResult.Error("文件名称不能为空"))
                return@launch
            }

            fileSystem.renameFile(fileId, trimmedName)
                .onSuccess {
                    loadFiles(folderId)
                    _operationResult.emit(OperationResult.Success("文件已重命名"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "重命名失败"))
                }
        }
    }

    /**
     * 把卡内文件 [fileId] 真实导出到系统选取器（`CreateDocument`）返回的 [uri]（M11.5.2）。
     * 真卡读隐藏区明文流式写出；Mock 写占位说明。导出受 UI 拷贝策略门控（NO_COPY 不可见）。
     */
    fun exportFileToUri(fileId: String, fileName: String, size: Long, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            transferCancelled = false
            _fileExportProgress.value = FileByteProgress(fileName, 0L, size)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    fileSystem.exportFile(fileId, fileName, out, isCancelled = { transferCancelled }) { written ->
                        _fileExportProgress.value = FileByteProgress(fileName, written, size)
                    }.getOrThrow()
                } ?: throw IOException("无法写入目标位置")
            }.onSuccess {
                operationLog.record(OperationType.FILE_EXPORT, "导出「$fileName」")
                _fileExportResult.value = ExportResult(true, "已导出「$fileName」到所选位置")
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
     * 子目录，再把文件夹内下一级所有文件原样（不压缩、保留文件名）逐个流式写入。按文件个数回报进度。
     */
    fun exportFolderToTree(folderId: String, folderName: String, treeUri: Uri) {
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
                // 先建目标文档，留引用以便取消时删半成品。
                val target = dir.createFile("application/octet-stream", f.name)
                runCatching {
                    (target ?: throw IOException("创建文件失败：${f.name}"))
                    context.contentResolver.openOutputStream(target.uri)?.use { out ->
                        fileSystem.exportFile(f.id, f.name, out, isCancelled = { transferCancelled }) { written ->
                            _exportProgress.value = ExportProgress(
                                i, files.size, currentFile = f.name, fileWritten = written, fileTotal = f.size
                            )
                        }.getOrThrow()
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
            val summary =
                if (cancelled) "已取消，已导出 $ok/${files.size} 个文件到「$folderName」"
                else "已导出 $ok/${files.size} 个文件到「$folderName」"
            operationLog.record(OperationType.FILE_EXPORT, "导出文件夹「$folderName」（$summary）")
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
    }
}
