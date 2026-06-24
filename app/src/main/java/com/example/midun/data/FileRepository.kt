package com.example.midun.data

import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.real.RealFileSystem
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私文件系统门面（M11.4）。App 只面向真卡隐藏区 [RealFileSystem]；本类统一转发 [FileSystemOps] 调用，
 * 并在增删后经 [SecurityCardManager] 刷新「已用空间」。
 *
 * 历史：曾按 `useRealCard` 开关在 Mock/Real 间路由，2026-06-24 砍掉模拟模式后收成真卡单路径。
 */
@Singleton
class FileRepository @Inject constructor(
    private val real: RealFileSystem,
    private val cardManager: SecurityCardManager
) : FileSystemOps {

    override suspend fun getFolders() = real.getFolders()
    override suspend fun getFilesInFolder(folderId: String) = real.getFilesInFolder(folderId)
    override suspend fun getTotalFileCount() = real.getTotalFileCount()
    override suspend fun createFolder(name: String, policy: CopyPolicy) = real.createFolder(name, policy)

    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ) = real.importFile(folderId, fileName, size, openStream, isCancelled, onProgress)
        .also { cardManager.refreshCapacity() } // 导入后刷新「已用空间」（取消时半成品已删，刷新也对）

    override suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (written: Long) -> Unit
    ) = real.exportFile(fileId, fileName, output, isCancelled, onProgress)

    override suspend fun readFileBytes(fileId: String) = real.readFileBytes(fileId)

    /** 加密导出（M12.5）：用导出口令把卡内文件重加密成便携 `.midun` 容器写到 [output]。 */
    suspend fun exportFileEncrypted(
        fileId: String,
        fileName: String,
        output: OutputStream,
        passphrase: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (written: Long) -> Unit = {}
    ) = real.exportFileEncrypted(fileId, fileName, output, passphrase, isCancelled, onProgress)

    override suspend fun moveFile(fileId: String, targetFolderId: String) =
        real.moveFile(fileId, targetFolderId)

    // 删除类操作后刷新「已用空间」（释放了卡内空间）。
    override suspend fun deleteFile(fileId: String) =
        real.deleteFile(fileId).also { cardManager.refreshCapacity() }
    override suspend fun deleteAllFilesInFolder(folderId: String) =
        real.deleteAllFilesInFolder(folderId).also { cardManager.refreshCapacity() }
    override suspend fun deleteFolder(folderId: String) =
        real.deleteFolder(folderId).also { cardManager.refreshCapacity() }
    override suspend fun renameFolder(folderId: String, newName: String) = real.renameFolder(folderId, newName)
    override suspend fun renameFile(fileId: String, newName: String) = real.renameFile(fileId, newName)

    /** 打开隐私文件夹内某文件为流式 InputStream（M11.5.3：发送该文件时用）。读卡内明文流。 */
    fun openFileStream(fileId: String): InputStream = real.openCardStream(fileId)

    /**
     * 卡内文件是否存在（file-transfer 阶段3）：预览前判断缓存是否已被 7 天 TTL 清理 → 过期降级。
     */
    fun cardFileExists(path: String): Boolean = real.exists(path)
}
