package com.example.midun.data.model

enum class FileType { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

enum class CopyPolicy { NO_COPY, COPY_PLAIN, COPY_ENCRYPTED }

data class FileItem(
    val id: String,
    val name: String,
    val type: FileType,
    val size: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val parentId: String? = null,
    val copyPolicy: CopyPolicy = CopyPolicy.NO_COPY,
    val source: String = "import"
)