package com.example.midun.data

import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import java.io.InputStream
import java.io.OutputStream

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
     * 流式导入一个文件（M11.5.1）：从 [openStream] 取字节、64KB 分块写入目标文件夹，[onProgress] 回报已写
     * 字节数。真卡实现落隐藏区（`writeFile`）；Mock 实现消费流更新进度但只记内存元数据。[size] 用于
     * UI 显示与 100MB 上限校验（调用方先校验，真卡层再兜底）。文件名冲突等错误经 [Result] 返回。
     */
    suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        onProgress: (written: Long) -> Unit
    ): Result<FileItem>

    /**
     * 把文件字节导出到 [output]（M11.5.2）。真卡读隐藏区**解密后明文**（卡内本就加密存储，App 层无独立
     * 密钥，故「加密拷贝」策略实际也是明文导出——诚实降级）；Mock 无真实字节，写占位说明。返回写出字节数，
     * **不关闭 [output]**（调用方负责）。
     */
    suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: OutputStream,
        onProgress: (written: Long) -> Unit = {}
    ): Result<Long>

    /** 读取文件全部字节（图片预览用，内存解密）。真卡读隐藏区明文；Mock 无真实内容 → 失败。 */
    suspend fun readFileBytes(fileId: String): Result<ByteArray>

    /**
     * 把文件 [fileId] 移动到隐私文件夹 [targetFolderId]（保留原文件名）。真卡实现走隐藏区跨目录
     * `SFRename`（失败回退卡内 read+write+delete）；Mock 实现改 `parentId`。目标文件夹已存在同名文件、
     * 或目标即当前文件夹时经 [Result] 失败返回。返回移动后的新 [FileItem]（id/parentId 已更新）。
     */
    suspend fun moveFile(fileId: String, targetFolderId: String): Result<FileItem>

    suspend fun deleteFile(fileId: String): Result<Unit>
    suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit>
    suspend fun deleteFolder(folderId: String): Result<Unit>
    suspend fun renameFolder(folderId: String, newName: String): Result<Unit>
    suspend fun renameFile(fileId: String, newName: String): Result<Unit>
}
