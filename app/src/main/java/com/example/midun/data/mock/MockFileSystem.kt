package com.example.midun.data.mock

import com.example.midun.data.FileSystemOps
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Singleton
class MockFileSystem @Inject constructor() : FileSystemOps {

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

    override suspend fun getFolders(): List<FileItem> = mockFolders.toList()

    override suspend fun getFilesInFolder(folderId: String): List<FileItem> =
        mockFiles.filter { it.parentId == folderId }

    /** 安全卡内文件总数（跨所有文件夹）。供首页设备状态卡的「文件数量」统计使用。 */
    override suspend fun getTotalFileCount(): Int = mockFiles.size

    override suspend fun createFolder(name: String, policy: CopyPolicy): Result<FileItem> {
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

    override suspend fun importFile(
        folderId: String,
        fileName: String,
        size: Long,
        openStream: () -> InputStream,
        onProgress: (written: Long) -> Unit
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        // Mock 无真实存储：消费流以驱动真实进度，但只记内存元数据。
        runCatching {
            openStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    onProgress(total)
                }
            }
        }
        val file = FileItem(
            id = "file_${System.currentTimeMillis()}",
            name = fileName,
            type = guessFileType(fileName),
            size = size,
            parentId = folderId
        )
        mockFiles.add(file)
        Result.success(file)
    }

    override suspend fun exportFile(
        fileId: String,
        fileName: String,
        output: java.io.OutputStream
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = "（模拟数据）$fileName\n当前为模拟模式，无真实文件内容。"
                .toByteArray(Charsets.UTF_8)
            output.write(bytes)
            bytes.size.toLong()
        }
    }

    override suspend fun readFileBytes(fileId: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("模拟模式无真实文件内容，无法预览"))

    override suspend fun deleteFile(fileId: String): Result<Unit> {
        delay(200)
        mockFiles.removeAll { it.id == fileId }
        return Result.success(Unit)
    }

    override suspend fun deleteAllFilesInFolder(folderId: String): Result<Unit> {
        delay(300)
        mockFiles.removeAll { it.parentId == folderId }
        return Result.success(Unit)
    }

    override suspend fun deleteFolder(folderId: String): Result<Unit> {
        delay(300)
        mockFolders.removeAll { it.id == folderId }
        mockFiles.removeAll { it.parentId == folderId }
        return Result.success(Unit)
    }

    override suspend fun renameFolder(folderId: String, newName: String): Result<Unit> {
        delay(200)
        val index = mockFolders.indexOfFirst { it.id == folderId }
        if (index == -1) {
            return Result.failure(Exception("文件夹不存在"))
        }
        mockFolders[index] = mockFolders[index].copy(name = newName)
        return Result.success(Unit)
    }

    override suspend fun renameFile(fileId: String, newName: String): Result<Unit> {
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
