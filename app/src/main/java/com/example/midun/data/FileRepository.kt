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

    override suspend fun readFileBytes(fileId: String) = active().readFileBytes(fileId)

    override suspend fun deleteFile(fileId: String) = active().deleteFile(fileId)
    override suspend fun deleteAllFilesInFolder(folderId: String) = active().deleteAllFilesInFolder(folderId)
    override suspend fun deleteFolder(folderId: String) = active().deleteFolder(folderId)
    override suspend fun renameFolder(folderId: String, newName: String) = active().renameFolder(folderId, newName)
    override suspend fun renameFile(fileId: String, newName: String) = active().renameFile(fileId, newName)

    /**
     * 打开隐私文件夹内某文件为流式 InputStream（M11.5.3：发送该文件时用）。真卡读卡内明文流；
     * 模拟模式无真实文件 → 抛异常（隐私文件夹来源发送=真卡专属，UI 已在真卡模式才提供入口）。
     */
    fun openFileStream(fileId: String): java.io.InputStream =
        if (cardManager.useRealCard.value) real.openCardStream(fileId)
        else throw IllegalStateException("模拟模式无真实文件可发送")

    /**
     * 卡内文件是否存在（file-transfer 阶段3）：预览前判断缓存是否已被 7 天 TTL 清理 → 过期降级。
     * 模拟模式无真实卡文件 → 一律 false（聊天文件传输/预览本就真卡专属，不会在模拟模式命中）。
     */
    fun cardFileExists(path: String): Boolean =
        if (cardManager.useRealCard.value) real.exists(path) else false
}
