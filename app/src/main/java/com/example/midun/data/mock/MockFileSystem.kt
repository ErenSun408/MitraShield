package com.example.midun.data.mock

import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class MockFileSystem @Inject constructor() {

    private val mockFolders = mutableListOf(
        FileItem("folder_1", "工作文件", FileType.FOLDER, copyPolicy = CopyPolicy.COPY_PLAIN),
        FileItem("folder_2", "私人资料", FileType.FOLDER, copyPolicy = CopyPolicy.NO_COPY),
        FileItem("folder_3", "项目文档", FileType.FOLDER, copyPolicy = CopyPolicy.COPY_ENCRYPTED),
    )

    private val mockFiles = mutableListOf(
        FileItem("file_1", "合同草案_v3.pdf", FileType.DOCUMENT, 2048000L, parentId = "folder_1"),
        FileItem("file_2", "会议记录.docx", FileType.DOCUMENT, 512000L, parentId = "folder_1"),
        FileItem("file_3", "身份证扫描件.jpg", FileType.IMAGE, 1024000L, parentId = "folder_2"),
        FileItem("file_4", "项目说明.pdf", FileType.DOCUMENT, 3072000L, parentId = "folder_3"),
        FileItem("file_5", "演示视频.mp4", FileType.VIDEO, 52428800L, parentId = "folder_3", source = "chat"),
    )

    fun getFolders(): List<FileItem> = mockFolders.toList()

    fun getFilesInFolder(folderId: String): List<FileItem> =
        mockFiles.filter { it.parentId == folderId }

    /** 安全卡内文件总数（跨所有文件夹）。供首页设备状态卡的「文件数量」统计使用。 */
    fun getTotalFileCount(): Int = mockFiles.size

    suspend fun createFolder(name: String, policy: CopyPolicy): Result<FileItem> {
        delay(300)
        val folder = FileItem(
            id = "folder_${System.currentTimeMillis()}",
            name = name,
            type = FileType.FOLDER,
            copyPolicy = policy
        )
        mockFolders.add(folder)
        return Result.success(folder)
    }

    suspend fun importFile(folderId: String, fileName: String, fileSize: Long): Result<FileItem> {
        delay(500)
        val file = FileItem(
            id = "file_${System.currentTimeMillis()}",
            name = fileName,
            type = guessFileType(fileName),
            size = fileSize,
            parentId = folderId
        )
        mockFiles.add(file)
        return Result.success(file)
    }

    suspend fun deleteFile(fileId: String): Result<Unit> {
        delay(200)
        mockFiles.removeAll { it.id == fileId }
        return Result.success(Unit)
    }

    suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit> {
        delay(300)
        mockFiles.removeAll { it.parentId == folderId }
        return Result.success(Unit)
    }

    suspend fun deleteFolder(folderId: String): Result<Unit> {
        delay(300)
        mockFolders.removeAll { it.id == folderId }
        mockFiles.removeAll { it.parentId == folderId }
        return Result.success(Unit)
    }

    suspend fun renameFolder(folderId: String, newName: String): Result<Unit> {
        delay(200)
        val index = mockFolders.indexOfFirst { it.id == folderId }
        if (index == -1) {
            return Result.failure(Exception("文件夹不存在"))
        }
        mockFolders[index] = mockFolders[index].copy(name = newName)
        return Result.success(Unit)
    }

    suspend fun renameFile(fileId: String, newName: String): Result<Unit> {
        delay(200)
        val index = mockFiles.indexOfFirst { it.id == fileId }
        if (index == -1) {
            return Result.failure(Exception("文件不存在"))
        }
        mockFiles[index] = mockFiles[index].copy(name = newName)
        return Result.success(Unit)
    }

    /** 整卡擦除时调用：清空所有文件夹与文件。由 MockUsbManager.wipeAll() 统一触发。 */
    fun clear() {
        mockFolders.clear()
        mockFiles.clear()
    }

    private fun guessFileType(fileName: String): FileType =
        when (fileName.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp" -> FileType.IMAGE
            "mp4", "mkv", "avi", "mov" -> FileType.VIDEO
            "mp3", "aac", "wav", "m4a" -> FileType.AUDIO
            "pdf", "doc", "docx", "txt", "xls", "xlsx" -> FileType.DOCUMENT
            else -> FileType.OTHER
        }
}
