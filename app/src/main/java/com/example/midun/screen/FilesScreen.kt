package com.example.midun.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileType
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ExportProgress
import com.example.midun.viewmodel.FileViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit,
    fileViewModel: FileViewModel = hiltViewModel()
) {
    val uiState by fileViewModel.uiState.collectAsState()
    val exportProgress by fileViewModel.exportProgress.collectAsState()
    var folderToDelete by remember { mutableStateOf<FileItem?>(null) }
    // 文件夹导出：选目标目录（OpenDocumentTree）→ 在其下建同名子目录写入全部文件（M11.5.6）。
    var pendingExportFolder by remember { mutableStateOf<FileItem?>(null) }
    val folderExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val f = pendingExportFolder
        if (treeUri != null && f != null) fileViewModel.exportFolderToTree(f.id, f.name, treeUri)
        pendingExportFolder = null
    }

    LaunchedEffect(Unit) {
        fileViewModel.loadFolders()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateFolder,
                containerColor = Primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.CreateNewFolder, "新建文件夹")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = Primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("隐私文件夹", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("所有文件加密存储于USB安全卡EMMC中", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
            }

            if (uiState.folders.isEmpty()) {
                item {
                    EmptyFoldersState(onCreateFolder = onCreateFolder)
                }
            } else {
                items(uiState.folders, key = { it.id }) { folder ->
                    FolderCard(
                        folder = folder,
                        onClick = { onFolderClick(folder.id) },
                        onRename = { newName -> fileViewModel.renameFolder(folder.id, newName) },
                        onExportFolder = {
                            pendingExportFolder = folder
                            folderExportLauncher.launch(null)
                        },
                        onDelete = { folderToDelete = folder }
                    )
                }
            }
        }
    }

    folderToDelete?.let { folder ->
        DeleteConfirmDialog(
            title = "删除文件夹",
            message = "确定删除「${folder.name}」？文件夹内所有文件也会被删除，且无法恢复。",
            onDismiss = { folderToDelete = null },
            onConfirm = {
                fileViewModel.deleteFolder(folder.id)
                folderToDelete = null
            }
        )
    }

    exportProgress?.let { FolderExportDialog(it) { fileViewModel.clearExportProgress() } }
}

/** 文件夹导出进度/结果对话框（M11.5.6）。进行中显进度条+计数（不可关），完成显结果（可关）。 */
@Composable
private fun FolderExportDialog(progress: ExportProgress, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (progress.finished) onDismiss() },
        icon = { Icon(Icons.Default.FolderZip, null, tint = Primary) },
        title = { Text(if (progress.finished) "导出完成" else "正在导出文件夹") },
        text = {
            Column {
                if (progress.finished) {
                    Text(progress.message, color = TextSecondary)
                } else {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("已导出 ${progress.done}/${progress.total}", fontSize = 12.sp, color = TextSecondary)
                }
            }
        },
        confirmButton = {
            if (progress.finished) {
                TextButton(onClick = onDismiss) { Text("确定", color = Primary) }
            }
        }
    )
}

@Composable
private fun EmptyFoldersState(onCreateFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FolderOff, null, tint = TextSecondary.copy(alpha = 0.55f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("暂无隐私文件夹", color = TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("创建文件夹后，文件会加密存储在安全卡中", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onCreateFolder) {
            Text("创建第一个文件夹", color = Primary)
        }
    }
}

@Composable
private fun FolderCard(
    folder: FileItem,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onExportFolder: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
    val canExport = folder.copyPolicy != CopyPolicy.NO_COPY

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Primary.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Folder, null, tint = Primary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Row {
                    Text("创建于 ${formatFolderDate(folder.createdAt)}", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("安全卡存储", fontSize = 12.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (folder.copyPolicy == CopyPolicy.NO_COPY) Danger.copy(0.15f) else Accent.copy(0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            folder.copyPolicy.label(),
                            fontSize = 10.sp,
                            color = if (folder.copyPolicy == CopyPolicy.NO_COPY) Danger else Accent
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Primary.copy(0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("AES-256加密", fontSize = 10.sp, color = Primary)
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null, tint = TextSecondary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = Primary) },
                        onClick = {
                            renameText = folder.name
                            showRenameDialog = true
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (canExport) "导出文件夹" else "不可导出",
                                color = if (canExport) TextPrimary else TextSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FolderZip,
                                null,
                                tint = if (canExport) Primary else TextSecondary
                            )
                        },
                        enabled = canExport,
                        onClick = {
                            showMenu = false
                            onExportFolder()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Danger) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            title = "重命名文件夹",
            value = renameText,
            onValueChange = { renameText = it },
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                onRename(renameText)
                showRenameDialog = false
            }
        )
    }
}

private fun CopyPolicy.label(): String =
    when (this) {
        CopyPolicy.NO_COPY -> "不可拷贝"
        CopyPolicy.COPY_PLAIN -> "拷贝明文"
        CopyPolicy.COPY_ENCRYPTED -> "拷贝密文"
    }

private fun CopyPolicy.exportLabel(): String =
    when (this) {
        CopyPolicy.NO_COPY -> "不可导出"
        CopyPolicy.COPY_PLAIN -> "明文导出"
        CopyPolicy.COPY_ENCRYPTED -> "密文导出"
    }

private fun formatFolderDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))

private fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.CHINA, bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(Locale.CHINA, bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(Locale.CHINA, bytes / (1024.0 * 1024 * 1024))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    folderId: String,
    onBack: () -> Unit,
    fileViewModel: FileViewModel = hiltViewModel()
) {
    val uiState by fileViewModel.uiState.collectAsState()
    val importProgress by fileViewModel.importProgress.collectAsState()
    val folder = uiState.folders.find { it.id == folderId }
    val files = if (uiState.currentFolderId == folderId) uiState.currentFiles else emptyList()
    val effectiveCopyPolicy = folder?.copyPolicy ?: CopyPolicy.NO_COPY
    var showMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 系统文件选取器（GetContent）：选中即真实流式导入到本文件夹（M11.5.1）。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { fileViewModel.importFromUri(folderId, it) }
    }
    // 系统保存选取器（CreateDocument）：选好位置即真实流式导出该文件（M11.5.2）。
    var fileToExport by remember { mutableStateOf<FileItem?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = fileToExport
        if (uri != null && f != null) fileViewModel.exportFileToUri(f.id, f.name, uri)
        fileToExport = null
    }
    // 文件夹整体导出：选目标目录 → 建同名子目录写入全部文件（M11.5.6）。
    val exportProgress by fileViewModel.exportProgress.collectAsState()
    val fileExportResult by fileViewModel.fileExportResult.collectAsState()
    val folderExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null && folder != null) fileViewModel.exportFolderToTree(folder.id, folder.name, treeUri)
    }
    var fileToDelete by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteAllFilesDialog by remember { mutableStateOf(false) }
    val onExportAllFiles = {
        showMenu = false
        folderExportLauncher.launch(null)
    }
    val onDeleteAllFiles = {
        showMenu = false
        showDeleteAllFilesDialog = true
    }

    LaunchedEffect(folderId) {
        fileViewModel.loadFolders()
        fileViewModel.loadFiles(folderId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folder?.name ?: "文件夹")
                        Text(
                            folder?.copyPolicy?.label() ?: "",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.FileUpload, "导入")
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (effectiveCopyPolicy == CopyPolicy.NO_COPY) "不可导出" else "导出全部文件",
                                    color = if (effectiveCopyPolicy == CopyPolicy.NO_COPY) TextSecondary else TextPrimary
                                )
                            },
                            onClick = onExportAllFiles,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.FileDownload,
                                    null,
                                    tint = if (effectiveCopyPolicy == CopyPolicy.NO_COPY) TextSecondary else Primary
                                )
                            },
                            enabled = files.isNotEmpty() && effectiveCopyPolicy != CopyPolicy.NO_COPY
                        )
                        DropdownMenuItem(
                            text = { Text("删除全部文件", color = Danger) },
                            onClick = onDeleteAllFiles,
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = Danger) },
                            enabled = files.isNotEmpty()
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOff, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无文件", color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("导入后会显示在当前隐私文件夹中", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.FileUpload, null, tint = Primary)
                        Spacer(Modifier.width(6.dp))
                        Text("导入第一个文件", color = Primary)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("${files.size}", fontWeight = FontWeight.Bold, color = Primary)
                                Text("文件总数", fontSize = 11.sp, color = TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    folder?.copyPolicy?.label() ?: "-",
                                    fontWeight = FontWeight.Bold,
                                    color = if (folder?.copyPolicy == CopyPolicy.NO_COPY) Danger else Accent
                                )
                                Text("拷贝策略", fontSize = 11.sp, color = TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("AES-256", fontWeight = FontWeight.Bold, color = Primary)
                                Text("加密方式", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                items(files, key = { it.id }) { file ->
                    FileItemCard(
                        file = file,
                        copyPolicy = effectiveCopyPolicy,
                        onRename = { newName -> fileViewModel.renameFile(file.id, newName, folderId) },
                        onExportFile = {
                            fileToExport = file
                            exportLauncher.launch(file.name)
                        },
                        onDelete = { fileToDelete = file }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            icon = { Icon(Icons.Default.FileUpload, null, tint = Primary) },
            title = { Text("导入文件") },
            text = {
                Column {
                    Text("选择导入来源：", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            importLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null)
                        Spacer(Modifier.width(8.dp))
                        Text("从手机存储导入")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            importLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Usb, null)
                        Spacer(Modifier.width(8.dp))
                        Text("从普通U盘导入")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在系统选取器中选择文件（手机存储或已挂载的U盘），单文件上限 100MB",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            }
        )
    }

    importProgress?.let { p ->
        val mb = { bytes: Long -> "%.1f MB".format(bytes / 1024f / 1024f) }
        AlertDialog(
            onDismissRequest = { }, // 导入中不可取消关闭
            icon = { Icon(Icons.Default.FileUpload, null, tint = Primary) },
            title = { Text("正在导入") },
            text = {
                Column {
                    Text(p.fileName, fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    if (p.total > 0) {
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${mb(p.written)} / ${mb(p.total)}（${(p.fraction * 100).toInt()}%）",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("已写入 ${mb(p.written)}", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            },
            confirmButton = {}
        )
    }

    fileToDelete?.let { file ->
        DeleteConfirmDialog(
            title = "删除文件",
            message = "确定删除「${file.name}」？此操作不可恢复。",
            onDismiss = { fileToDelete = null },
            onConfirm = {
                fileViewModel.deleteFile(file.id, folderId)
                fileToDelete = null
            }
        )
    }

    if (showDeleteAllFilesDialog) {
        DeleteConfirmDialog(
            title = "删除全部文件",
            message = "确定删除「${folder?.name ?: "当前文件夹"}」内的全部文件？文件夹会保留，但文件无法恢复。",
            onDismiss = { showDeleteAllFilesDialog = false },
            onConfirm = {
                fileViewModel.deleteAllFilesInFolder(folderId)
                showDeleteAllFilesDialog = false
            }
        )
    }

    exportProgress?.let { FolderExportDialog(it) { fileViewModel.clearExportProgress() } }

    fileExportResult?.let { r ->
        AlertDialog(
            onDismissRequest = { fileViewModel.clearFileExportResult() },
            icon = {
                Icon(
                    if (r.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    tint = if (r.success) Success else Danger
                )
            },
            title = { Text(if (r.success) "导出成功" else "导出失败") },
            text = { Text(r.message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { fileViewModel.clearFileExportResult() }) { Text("确定", color = Primary) }
            }
        )
    }
}

@Composable
private fun FileItemCard(
    file: FileItem,
    copyPolicy: CopyPolicy,
    onRename: (String) -> Unit,
    onExportFile: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(file.id, file.name) { mutableStateOf(file.name) }
    val canExport = copyPolicy != CopyPolicy.NO_COPY
    val iconData = when (file.type) {
        FileType.FOLDER -> Pair(Icons.Default.Folder, Primary)
        FileType.DOCUMENT -> Pair(Icons.Default.Description, Primary)
        FileType.IMAGE -> Pair(Icons.Default.Image, Accent)
        FileType.VIDEO -> Pair(Icons.Default.VideoFile, Warning)
        FileType.AUDIO -> Pair(Icons.Default.AudioFile, Success)
        FileType.OTHER -> Pair(Icons.Default.InsertDriveFile, TextSecondary)
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(iconData.second.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconData.first, null, tint = iconData.second, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row {
                    Text(formatFileSize(file.size), fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(formatFolderDate(file.createdAt), fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (file.source == "chat") "来自聊天" else "手动导入", fontSize = 11.sp, color = Accent)
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null, tint = TextSecondary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = Primary) },
                        onClick = {
                            renameText = file.name
                            showRenameDialog = true
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (canExport) "导出" else "不可导出",
                                color = if (canExport) TextPrimary else TextSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FileDownload,
                                null,
                                tint = if (canExport) Primary else TextSecondary
                            )
                        },
                        enabled = canExport,
                        onClick = {
                            showMenu = false
                            onExportFile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Danger) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            title = "重命名文件",
            value = renameText,
            onValueChange = { renameText = it },
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                onRename(renameText)
                showRenameDialog = false
            }
        )
    }
}

@Composable
private fun RenameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, null, tint = Primary) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
        title = { Text(title, color = Danger) },
        text = { Text(message, color = TextSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFolderScreen(
    onBack: () -> Unit,
    fileViewModel: FileViewModel = hiltViewModel()
) {
    var folderName by remember { mutableStateOf("") }
    var copyMode by remember { mutableIntStateOf(0) } // 0=不可拷贝 1=拷贝明文 2=拷贝密文
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val uiState by fileViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        fileViewModel.operationResult.collect { result ->
            when (result) {
                is FileViewModel.OperationResult.Success -> showSuccess = true
                is FileViewModel.OperationResult.Error -> errorMessage = result.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建隐私文件夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                leadingIcon = { Icon(Icons.Default.Folder, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))

            Text("拷贝策略", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("控制文件夹中文件的拷贝权限", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))

            listOf("不可拷贝", "拷贝明文", "拷贝密文").forEachIndexed { index, label ->
                val desc = when (index) {
                    0 -> "文件无法被拷贝出文件夹"
                    1 -> "文件拷贝时以明文形式导出"
                    else -> "文件拷贝时保持加密状态"
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { copyMode = index },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (copyMode == index) Primary.copy(0.08f) else CardBg
                    ),
                    border = if (copyMode == index)
                        CardDefaults.outlinedCardBorder().copy(width = 2.dp)
                    else null
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = copyMode == index, onClick = { copyMode = index },
                            colors = RadioButtonDefaults.colors(selectedColor = Primary))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(desc, fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    errorMessage = null
                    fileViewModel.createFolder(folderName.trim(), copyMode.toCopyPolicy())
                },
                enabled = folderName.isNotBlank() && !uiState.isLoading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建文件夹")
                }
            }

            errorMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = Danger, fontSize = 12.sp)
            }
        }
    }

    if (showSuccess) {
        AlertDialog(
            onDismissRequest = { showSuccess = false; onBack() },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = Success) },
            title = { Text("创建成功") },
            text = { Text("隐私文件夹「$folderName」已在USB安全卡EMMC中创建") },
            confirmButton = {
                TextButton(onClick = { showSuccess = false; onBack() }) { Text("确定") }
            }
        )
    }
}

private fun Int.toCopyPolicy(): CopyPolicy =
    when (this) {
        1 -> CopyPolicy.COPY_PLAIN
        2 -> CopyPolicy.COPY_ENCRYPTED
        else -> CopyPolicy.NO_COPY
    }
