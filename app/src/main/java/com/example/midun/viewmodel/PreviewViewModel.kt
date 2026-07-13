package com.example.midun.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.example.midun.data.FileCachePaths
import com.example.midun.data.FileRepository
import com.example.midun.data.local.LocalFileSystem
import com.example.midun.data.staging.StagingStore
import com.example.midun.media.VaultFileDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 文件预览数据访问（方案 B）。图片走 [FileRepository.readImageBytes]（暂存缓存命中则读 StagingStore，
 * 否则本地内存解密）；视频用 [videoFactory]/[videoUri] 构造流式 DataSource。
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val fileRepo: FileRepository,
    private val stagingStore: StagingStore,
    /** 隐私文件夹视频流式 DataSource（本地随机读解密）用；暂存缓存改走 [videoFactory]。 */
    val localFileSystem: LocalFileSystem
) : ViewModel() {

    suspend fun readImageBytes(fileId: String): Result<ByteArray> = fileRepo.readFileBytes(fileId)

    /**
     * 视频预览的 media3 DataSource 工厂：聊天暂存缓存（`.recv_`/`.sent_`）走 [StagingStore]（无卡测试落本地
     * 文件直读），隐私文件夹文件走卡内流式解密（[VaultFileDataSource]）。
     */
    @UnstableApi
    fun videoFactory(path: String): DataSource.Factory =
        if (FileCachePaths.isCachePath(path)) stagingStore.videoDataSourceFactory(path)
        else VaultFileDataSource.Factory(localFileSystem, path)

    /** 喂给 ExoPlayer 的 MediaItem uri：暂存缓存由 [StagingStore] 给（本地为真实 file://），隐私文件夹用占位。 */
    fun videoUri(path: String): Uri =
        if (FileCachePaths.isCachePath(path)) stagingStore.videoUri(path) else Uri.parse("card://preview")
}
