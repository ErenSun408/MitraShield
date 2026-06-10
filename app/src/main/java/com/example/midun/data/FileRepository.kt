package com.example.midun.data

import com.example.midun.data.mock.MockFileSystem
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.real.RealFileSystem
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私文件系统门面（M11.4）。持 [MockFileSystem]（模拟）与 [RealFileSystem]（真卡隐藏区）两套实现，
 * 按 [SecurityCardManager.useRealCard] 开关把 [FileSystemOps] 调用路由到对应实现——与认证层 [SecurityCardManager]
 * 的 Mock/Real 路由同构、共用同一个开关。上层 `FileViewModel` 统一注入本类。
 *
 * 切换开关时上层会重走 Splash（M11.3 设计），`FileViewModel` 随之重建并重新读列表，故每次调用即时
 * 读 `useRealCard.value` 选实现即可。
 */
@Singleton
class FileRepository @Inject constructor(
    private val mock: MockFileSystem,
    private val real: RealFileSystem,
    private val cardManager: SecurityCardManager
) : FileSystemOps {

    private fun active(): FileSystemOps = if (cardManager.useRealCard.value) real else mock

    override suspend fun getFolders() = active().getFolders()
    override suspend fun getFilesInFolder(folderId: String) = active().getFilesInFolder(folderId)
    override suspend fun getTotalFileCount() = active().getTotalFileCount()
    override suspend fun createFolder(name: String, policy: CopyPolicy) = active().createFolder(name, policy)
    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        onProgress: (written: Long) -> Unit
    ) = active().importFile(folderId, fileName, size, openStream, onProgress)

    override suspend fun exportFile(fileId: String, fileName: String, output: OutputStream) =
        active().exportFile(fileId, fileName, output)

    override suspend fun deleteFile(fileId: String) = active().deleteFile(fileId)
    override suspend fun deleteAllFilesInFolder(folderId: String) = active().deleteAllFilesInFolder(folderId)
    override suspend fun deleteFolder(folderId: String) = active().deleteFolder(folderId)
    override suspend fun renameFolder(folderId: String, newName: String) = active().renameFolder(folderId, newName)
    override suspend fun renameFile(fileId: String, newName: String) = active().renameFile(fileId, newName)
}
