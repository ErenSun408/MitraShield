package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import com.example.midun.data.FileRepository
import com.example.midun.data.real.RealFileSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 文件预览数据访问（方案 B）。图片走 [FileRepository.readFileBytes]（内存解密）；视频由 UI 用
 * [realFileSystem] 构造卡内流式 DataSource。
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val fileRepo: FileRepository,
    /** 暴露给 UI 构造视频流式 DataSource（卡内随机读）。 */
    val realFileSystem: RealFileSystem
) : ViewModel() {

    suspend fun readImageBytes(fileId: String): Result<ByteArray> = fileRepo.readFileBytes(fileId)
}
