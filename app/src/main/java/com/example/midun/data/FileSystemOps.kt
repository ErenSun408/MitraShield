package com.example.midun.data

import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem

/**
 * 隐私文件夹文件系统的统一抽象（M11.4）。由 [com.example.midun.data.mock.MockFileSystem]（模拟）与
 * [com.example.midun.data.real.RealFileSystem]（真卡隐藏区）两套实现，经 [FileRepository] 按
 * `SecurityCardManager.useRealCard` 开关路由——与 [UsbCardOps]/[SecurityCardManager] 的认证层路由同构。
 *
 * **读方法为 `suspend`**：真卡读列表/大小是阻塞原生 IO（`SFGetFileList`/`SFGetSize`），不能在主线程同步调用，
 * 故连同 Mock 的内存读一并提升为 `suspend`（Mock 实现里只是直接返回）。
 *
 * **id 约定**：真卡实现用「隐藏区完整路径」当 id（文件夹 `0:/工作文件`、文件 `0:/工作文件/a.pdf`），
 * `parentId` = 所属文件夹路径；Mock 实现保持原有 `folder_x`/`file_x` 合成 id。上层只把 id 当不透明字符串用。
 */
interface FileSystemOps {
    suspend fun getFolders(): List<FileItem>
    suspend fun getFilesInFolder(folderId: String): List<FileItem>

    /** 卡内文件总数（跨所有文件夹），供首页设备状态卡统计。 */
    suspend fun getTotalFileCount(): Int

    suspend fun createFolder(name: String, policy: CopyPolicy): Result<FileItem>

    /**
     * 在文件夹内登记一个文件。**M11.4 只建立卡内文件条目（空文件）**——真实字节流式导入（选取器 +
     * 64KB 分块 + 100MB 限制）是 M11.5；`fileSize` 仅用于 Mock 占位展示。
     */
    suspend fun importFile(folderId: String, fileName: String, fileSize: Long): Result<FileItem>

    suspend fun deleteFile(fileId: String): Result<Unit>
    suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit>
    suspend fun deleteFolder(folderId: String): Result<Unit>
    suspend fun renameFolder(folderId: String, newName: String): Result<Unit>
    suspend fun renameFile(fileId: String, newName: String): Result<Unit>
}
