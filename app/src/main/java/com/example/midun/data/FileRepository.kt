package com.example.midun.data

import com.example.midun.data.local.LocalFileSystem
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.staging.StagingStore
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私文件系统门面（无卡版）。App 面向纯软件本地隐私库 [LocalFileSystem]；本类统一转发 [FileSystemOps] 调用，
 * 并在增删后经 [AccountManager] 刷新「已用空间」。
 *
 * 历史：真卡版转发到 `RealFileSystem`（FSShell 隐藏区）；bobo-nocard 收成本地单路径。
 */
@Singleton
class FileRepository @Inject constructor(
    private val local: LocalFileSystem,
    private val accountManager: AccountManager,
    private val stagingStore: StagingStore
) : FileSystemOps {

    override suspend fun getFolders() = local.getFolders()
    override suspend fun getFilesInFolder(folderId: String) = local.getFilesInFolder(folderId)
    override suspend fun getTotalFileCount() = local.getTotalFileCount()
    override suspend fun createFolder(name: String, policy: CopyPolicy) = local.createFolder(name, policy)

    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ) = local.importFile(folderId, fileName, size, openStream, isCancelled, onProgress)
        .also { accountManager.refreshCapacity() } // 导入后刷新「已用空间」（取消时半成品已删，刷新也对）

    override suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ) = local.exportFile(fileId, fileName, output, isCancelled, onProgress)

    // 聊天暂存缓存（.recv_/.sent_）走 StagingStore；隐私文件夹走本地隐私库。下同 openFileStream/cardFileExists。
    override suspend fun readFileBytes(fileId: String) =
        if (FileCachePaths.isCachePath(fileId)) {
            stagingStore.readBytes(fileId)?.let { Result.success(it) }
                ?: Result.failure(java.io.IOException("暂存文件不存在或已清理：$fileId"))
        } else {
            local.readFileBytes(fileId)
        }

    /** 加密导出（M12.5）：用导出口令把隐私库文件重加密成便携 `.midun` 容器写到 [output]。 */
    suspend fun exportFileEncrypted(
        fileId: String,
        fileName: String,
        output: OutputStream,
        passphrase: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (written: Long) -> Unit = {}
    ) = local.exportFileEncrypted(fileId, fileName, output, passphrase, isCancelled, onProgress)

    override suspend fun moveFile(fileId: String, targetFolderId: String) =
        local.moveFile(fileId, targetFolderId)

    // 删除类操作后刷新「已用空间」（释放了本地空间）。
    override suspend fun deleteFile(fileId: String) =
        local.deleteFile(fileId).also { accountManager.refreshCapacity() }
    override suspend fun deleteAllFilesInFolder(folderId: String) =
        local.deleteAllFilesInFolder(folderId).also { accountManager.refreshCapacity() }
    override suspend fun deleteFolder(folderId: String) =
        local.deleteFolder(folderId).also { accountManager.refreshCapacity() }
    override suspend fun renameFolder(folderId: String, newName: String) = local.renameFolder(folderId, newName)
    override suspend fun renameFile(fileId: String, newName: String) = local.renameFile(fileId, newName)

    /** 打开文件为流式 InputStream（发送 / 语音回放 / 重发）。暂存缓存走 StagingStore，隐私文件夹读本地明文流。 */
    fun openFileStream(fileId: String): InputStream =
        if (FileCachePaths.isCachePath(fileId)) stagingStore.openRead(fileId) else local.openCardStream(fileId)

    /**
     * 文件是否存在（file-transfer 阶段3）：预览前判断缓存是否已被 7 天 TTL 清理 → 过期降级。
     * 暂存缓存走 StagingStore，隐私文件夹查本地隐私库。
     */
    fun cardFileExists(path: String): Boolean =
        if (FileCachePaths.isCachePath(path)) stagingStore.exists(path) else local.exists(path)
}
