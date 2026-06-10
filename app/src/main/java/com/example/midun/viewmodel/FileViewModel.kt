package com.example.midun.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.FileRepository
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

/** 文件导入进度（M11.5.1）。`total<=0` 表示来源未报大小，进度不确定。 */
data class ImportProgress(val fileName: String, val written: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
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
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

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
            _importProgress.value = ImportProgress(name, 0L, size)
            fileSystem.importFile(
                folderId = folderId,
                fileName = name,
                size = size,
                openStream = {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("打开文件失败")
                },
                onProgress = { written -> _importProgress.value = ImportProgress(name, written, size) }
            ).onSuccess {
                loadFiles(folderId)
                operationLog.record(OperationType.FILE_IMPORT, "导入「$name」")
                _operationResult.emit(OperationResult.Success("文件导入成功"))
            }.onFailure {
                _operationResult.emit(OperationResult.Error(it.message ?: "导入失败"))
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

    /** 记录一次导出操作。导出本身仍是 FilesScreen 的 mock 占位（M5），此处仅落日志。 */
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
