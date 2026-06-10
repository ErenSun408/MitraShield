package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.FileRepository
import com.example.midun.data.mock.MockOperationLog
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.OperationType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

@HiltViewModel
class FileViewModel @Inject constructor(
    private val fileSystem: FileRepository,
    private val operationLog: MockOperationLog
) : ViewModel() {

    sealed class OperationResult {
        data class Success(val message: String) : OperationResult()
        data class Error(val message: String) : OperationResult()
    }

    private val _uiState = MutableStateFlow(FileUiState())
    val uiState: StateFlow<FileUiState> = _uiState.asStateFlow()

    private val _operationResult = MutableSharedFlow<OperationResult>()
    val operationResult: SharedFlow<OperationResult> = _operationResult.asSharedFlow()

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

    fun importFile(folderId: String, fileName: String, fileSize: Long) {
        viewModelScope.launch {
            setLoading(true)
            fileSystem.importFile(folderId, fileName, fileSize)
                .onSuccess {
                    loadFiles(folderId)
                    operationLog.record(OperationType.FILE_IMPORT, "导入「$fileName」")
                    _operationResult.emit(OperationResult.Success("文件导入成功"))
                }
                .onFailure {
                    _operationResult.emit(OperationResult.Error(it.message ?: "导入失败"))
                }
            setLoading(false)
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

    /** 记录一次导出操作。导出本身仍是 FilesScreen 的 mock 占位（M5），此处仅落日志。 */
    fun recordExport(description: String) {
        operationLog.record(OperationType.FILE_EXPORT, description)
    }

    private fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }
}
