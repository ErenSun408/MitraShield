package com.example.midun.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.crypto.FileContainer
import com.example.midun.data.model.CopyPolicy
import com.example.midun.data.model.FileItem
import com.example.midun.data.model.FileSort
import com.example.midun.data.model.FileType
import com.example.midun.data.model.SortField
import com.example.midun.data.model.applySort
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ExportProgress
import com.example.midun.viewmodel.FileByteProgress
import com.example.midun.viewmodel.FileViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit,
    snackbarHostState: SnackbarHostState,
    fileViewModel: FileViewModel = hiltViewModel()
) {
    val uiState by fileViewModel.uiState.collectAsState()
    val exportProgress by fileViewModel.exportProgress.collectAsState()
    // 排序只作用在展示上，uiState 里那份保持卡上原始顺序（切回别的排序才有得排）。
    val folderSort by fileViewModel.folderSort.collectAsState()
    val folders = remember(uiState.folders, folderSort) { uiState.folders.applySort(folderSort) }
    var folderToDelete by remember { mutableStateOf<FileItem?>(null) }
    // 文件夹导出：选目标目录（OpenDocumentTree）→ 在其下建同名子目录写入全部文件（M11.5.6 / M12.5 加密）。
    var pendingExportFolder by remember { mutableStateOf<FileItem?>(null) }
    // 拷贝密文策略（M12.5）：导出前收口令。[passphraseForFolder] 非空=正在为该文件夹收口令；[pendingExportPass]
    // 把确认后的口令带进选取器回调（明文策略保持 null → 走原明文导出）。
    var passphraseForFolder by remember { mutableStateOf<FileItem?>(null) }
    var pendingExportPass by remember { mutableStateOf<String?>(null) }
    // 导出目标弹框：点「导出文件夹」先选一次目标，确认后才跳系统选取器（客户反馈直接跳太突兀）。
    var exportTargetFolder by remember { mutableStateOf<FileItem?>(null) }
    val folderExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val f = pendingExportFolder
        if (treeUri != null && f != null) fileViewModel.exportFolderToTree(f.id, f.name, treeUri, pendingExportPass)
        pendingExportFolder = null
        pendingExportPass = null
    }

    LaunchedEffect(Unit) {
        fileViewModel.loadFolders()
    }

    // 结果反馈走底部 Snackbar（重命名/删除文件夹、文件夹导出完成）；确认弹窗保持模态。
    // snackbarHostState 由 MainScreen 托管 → Snackbar 落在底部导航栏之上、不被本屏 FAB 顶起。
    LaunchedEffect(Unit) {
        fileViewModel.operationResult.collect { r ->
            snackbarHostState.showSnackbar(
                when (r) {
                    is FileViewModel.OperationResult.Success -> r.message
                    is FileViewModel.OperationResult.Error -> r.message
                }
            )
        }
    }
    LaunchedEffect(exportProgress?.finished) {
        exportProgress?.takeIf { it.finished }?.let {
            snackbarHostState.showSnackbar(it.message)
            fileViewModel.clearExportProgress()
        }
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
                    Text("私藏清隅", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                // 副标题与排序选择器同行：左说明、右操作，省一行高度。空列表时不显示排序（没东西可排）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("所有文件存储于设备中", fontSize = 12.sp, color = TextSecondary)
                    if (folders.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        SortSelector(
                            sort = folderSort,
                            fields = FileSort.FOLDER_FIELDS,
                            onChange = fileViewModel::setFolderSort
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (folders.isEmpty()) {
                item {
                    EmptyFoldersState(onCreateFolder = onCreateFolder)
                }
            } else {
                items(folders, key = { it.id }) { folder ->
                    FolderCard(
                        folder = folder,
                        onClick = { onFolderClick(folder.id) },
                        onRename = { newName -> fileViewModel.renameFolder(folder.id, newName) },
                        onExportFolder = { exportTargetFolder = folder },
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

    // 仅「进行中」显进度弹窗；完成态由上面的 LaunchedEffect 转 Snackbar。
    exportProgress?.takeIf { !it.finished }?.let {
        FolderExportDialog(it, onCancel = { fileViewModel.cancelTransfer() })
    }

    // 导出目标弹框：确认「导出到手机」后才继续原流程（加密策略先收口令，否则直接选目录）。
    exportTargetFolder?.let { f ->
        ExportTargetDialog(
            itemName = f.name,
            isFolder = true,
            encrypted = f.copyPolicy == CopyPolicy.COPY_ENCRYPTED,
            onExportToPhone = {
                exportTargetFolder = null
                if (f.copyPolicy == CopyPolicy.COPY_ENCRYPTED) {
                    passphraseForFolder = f // 先收口令，再选目录
                } else {
                    pendingExportPass = null
                    pendingExportFolder = f
                    folderExportLauncher.launch(null)
                }
            },
            onDismiss = { exportTargetFolder = null }
        )
    }

    // 加密导出口令弹框（M12.5）：拷贝密文文件夹导出前收口令，确认后带口令选目录。
    passphraseForFolder?.let { f ->
        ExportPassphraseDialog(
            isFolder = true,
            onConfirm = { pass ->
                pendingExportPass = pass
                pendingExportFolder = f
                passphraseForFolder = null
                folderExportLauncher.launch(null)
            },
            onDismiss = { passphraseForFolder = null }
        )
    }
}

/** 文件夹导出进度对话框（M11.5.6 + M-files 当前文件字节进度）。仅进行中显示（不可关）；完成态走 Snackbar。
 *  [onCancel]：点「取消」中止导出（已导出的文件保留，当前半成品删除）。 */
@Composable
private fun FolderExportDialog(progress: ExportProgress, onCancel: () -> Unit) {
    val mb = { bytes: Long -> "%.1f MB".format(bytes / 1024f / 1024f) }
    AlertDialog(
        onDismissRequest = { }, // 进行中不可关（仅「取消」按钮可中止）
        icon = { Icon(Icons.Default.FolderZip, null, tint = Primary) },
        title = { Text("正在导出文件夹") },
        text = {
            Column {
                Text("已导出 ${progress.done}/${progress.total} 个文件", fontSize = 12.sp, color = TextSecondary)
                if (progress.currentFile.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(progress.currentFile, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    if (progress.fileTotal > 0) {
                        LinearProgressIndicator(progress = { progress.fileFraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${mb(progress.fileWritten)} / ${mb(progress.fileTotal)}",
                            fontSize = 11.sp, color = TextSecondary
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 单文件字节进度对话框（导入/单文件导出共用）。仅进行中显示，不可关；[onCancel] 中止并清半成品。 */
@Composable
private fun ByteProgressDialog(title: String, icon: ImageVector, p: FileByteProgress, onCancel: () -> Unit) {
    val mb = { bytes: Long -> "%.1f MB".format(bytes / 1024f / 1024f) }
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(icon, null, tint = Primary) },
        title = { Text(title) },
        text = {
            Column {
                Text(p.fileName, fontSize = 14.sp, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                if (p.total > 0) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${mb(p.written)} / ${mb(p.total)}（${(p.fraction * 100).toInt()}%）",
                        fontSize = 12.sp, color = TextSecondary
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("已处理 ${mb(p.written)}", fontSize = 12.sp, color = TextSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("取消", color = TextSecondary) }
        }
    )
}

/**
 * 排序选择器（客户 2026-08-14）：文件夹列表与文件列表共用这个控件，但各自的**可选依据与偏好是分开的**
 * ——[fields] 由调用方给（文件时间/名称两种，文件夹只有名称，原因见 [FileSort] 里那两组常量）。
 *
 * **照 Windows 资源管理器分成两栏**：上「排序方式」选依据、下「顺序」选递增/递减，中间一条分隔线。
 * 两栏各自单选、互不影响——改方向不用重新想按什么排，改依据也不会把方向重置掉。依据只有一种时（文件夹）
 * 上面那栏整个不画：一个选项的单选组没有意义，按钮上已经写着「名称」了。
 *
 * 按钮做成**一条小字**而不是分段按钮/一排 Chip：这两屏的主角是列表本身，排序是偶尔用一次的东西，不该占
 * 掉一整行的视觉重量。依据与方向都直接写在按钮上（「时间 ↓」），不点开也知道现在是怎么排的。
 */
@Composable
private fun SortSelector(sort: FileSort, fields: List<SortField>, onChange: (FileSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SwapVert, null, tint = Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(sort.field.label, fontSize = 12.sp, color = Primary)
            Icon(
                if (sort.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                null,
                tint = Primary,
                modifier = Modifier.size(13.dp)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = Primary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (fields.size > 1) {
                SortMenuHeader("排序方式")
                fields.forEach { field ->
                    SortMenuItem(
                        label = field.label,
                        selected = field == sort.field,
                        // 只换依据，方向原样带过去——这正是分两栏的意义。
                        onClick = { onChange(sort.copy(field = field)) }
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            SortMenuHeader("顺序")
            SortMenuItem("递增", selected = !sort.descending) { onChange(sort.copy(descending = false)) }
            SortMenuItem("递减", selected = sort.descending) { onChange(sort.copy(descending = true)) }
        }
    }
}

/** 排序菜单里的栏目名（「排序方式」「顺序」）。纯标题，不可点。 */
@Composable
private fun SortMenuHeader(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = TextSecondary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** 排序菜单里的单选项：选中的打勾；未选中留同宽空位，免得文字左右跳。**点了不关菜单**——见下方说明。 */
@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) Primary else TextPrimary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        },
        leadingIcon = {
            if (selected) {
                Icon(Icons.Default.Check, null, tint = Primary, modifier = Modifier.size(18.dp))
            } else {
                Spacer(Modifier.size(18.dp))
            }
        },
        onClick = onClick
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
        Text("暂无文件夹", color = TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("创建文件夹后，文件存储于设备中", color = TextSecondary, fontSize = 12.sp)
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
                    Text("设备存储", fontSize = 12.sp, color = TextSecondary)
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
                        Text("已加密", fontSize = 10.sp, color = Primary)
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

/** 卡上读不到创建时间时（[timestamp] <= 0）显示 `--`，不能拿「今天」冒充。 */
private fun formatFolderDate(timestamp: Long): String =
    if (timestamp <= 0L) "--"
    else SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))

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
    val pendingContainer by fileViewModel.pendingContainer.collectAsState() // 选中 .midun → 弹口令解密框
    val folder = uiState.folders.find { it.id == folderId }
    val fileSort by fileViewModel.fileSort.collectAsState()
    // 排序只作用在展示上；`files` 从此处起就是排好序的那份，下游的计数/空判断不受影响。
    val files = remember(uiState.currentFolderId, uiState.currentFiles, folderId, fileSort) {
        if (uiState.currentFolderId == folderId) uiState.currentFiles.applySort(fileSort) else emptyList()
    }
    val effectiveCopyPolicy = folder?.copyPolicy ?: CopyPolicy.NO_COPY
    var showMenu by remember { mutableStateOf(false) }
    // 系统文件选取器（GetContent）：选中即真实流式导入到本文件夹（M11.5.1）。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { fileViewModel.importFromUri(folderId, it) }
    }
    // 拷贝密文策略（M12.5）：导出前先收一个导出口令，文件重加密成便携 .midun 容器。这两个状态门控口令弹框，
    // [pendingExportPass] 把确认后的口令带进选取器回调（明文策略保持 null → 走原明文导出）。
    val encryptedPolicy = effectiveCopyPolicy == CopyPolicy.COPY_ENCRYPTED
    var passphraseForFile by remember { mutableStateOf<FileItem?>(null) }
    var passphraseForFolder by remember { mutableStateOf(false) }
    var pendingExportPass by remember { mutableStateOf<String?>(null) }
    // 系统保存选取器（CreateDocument）：选好位置即真实流式导出该文件（M11.5.2 / M12.5 加密）。
    var fileToExport by remember { mutableStateOf<FileItem?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = fileToExport
        if (uri != null && f != null) fileViewModel.exportFileToUri(f.id, f.name, f.size, uri, pendingExportPass)
        fileToExport = null
        pendingExportPass = null
    }
    // 文件夹整体导出：选目标目录 → 建同名子目录写入全部文件（M11.5.6 / M12.5 加密）。
    val exportProgress by fileViewModel.exportProgress.collectAsState()
    val fileExportResult by fileViewModel.fileExportResult.collectAsState()
    val fileExportProgress by fileViewModel.fileExportProgress.collectAsState()
    val folderExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null && folder != null) fileViewModel.exportFolderToTree(folder.id, folder.name, treeUri, pendingExportPass)
        pendingExportPass = null
    }
    var fileToDelete by remember { mutableStateOf<FileItem?>(null) }
    var previewFile by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteAllFilesDialog by remember { mutableStateOf(false) }
    // 导出目标弹框：单文件/整夹导出都先让用户选一次目标，确认后才跳系统选取器（客户反馈直接跳太突兀）。
    var exportTargetFile by remember { mutableStateOf<FileItem?>(null) }
    var exportTargetAllFiles by remember { mutableStateOf(false) }
    val onExportAllFiles = {
        showMenu = false
        exportTargetAllFiles = true
    }
    val onDeleteAllFiles = {
        showMenu = false
        showDeleteAllFilesDialog = true
    }

    LaunchedEffect(folderId) {
        fileViewModel.loadFolders()
        fileViewModel.loadFiles(folderId)
    }

    // 结果类反馈统一走底部 Snackbar（自动消失、不挡操作），替代原全屏确认弹窗。进度/确认弹窗保持模态。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        fileViewModel.operationResult.collect { r ->
            snackbarHostState.showSnackbar(
                when (r) {
                    is FileViewModel.OperationResult.Success -> r.message
                    is FileViewModel.OperationResult.Error -> r.message
                }
            )
        }
    }
    LaunchedEffect(fileExportResult) {
        fileExportResult?.let {
            snackbarHostState.showSnackbar(it.message)
            fileViewModel.clearFileExportResult()
        }
    }
    LaunchedEffect(exportProgress?.finished) {
        exportProgress?.takeIf { it.finished }?.let {
            snackbarHostState.showSnackbar(it.message)
            fileViewModel.clearExportProgress()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    // 导入入口带文字（客户反馈：纯图标不易识别是「导入」）。
                    TextButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入", color = Color.White)
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
                    Text("导入后会显示在当前文件夹中", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { importLauncher.launch("*/*") }) {
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
                                Text("已加密", fontWeight = FontWeight.Bold, color = Primary)
                                Text("加密方式", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                    // 排序选择器贴在统计卡与文件列表之间，右对齐——它管的是下面这串列表。
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SortSelector(
                            sort = fileSort,
                            fields = FileSort.FILE_FIELDS,
                            onChange = fileViewModel::setFileSort
                        )
                    }
                }

                items(files, key = { it.id }) { file ->
                    FileItemCard(
                        file = file,
                        copyPolicy = effectiveCopyPolicy,
                        moveTargets = uiState.folders.filter { it.id != folderId },
                        onRename = { newName -> fileViewModel.renameFile(file.id, newName, folderId) },
                        onMove = { target -> fileViewModel.moveFile(file.id, file.name, folderId, target.id) },
                        onExportFile = { exportTargetFile = file },
                        onDelete = { fileToDelete = file },
                        onPreview = { previewFile = file },
                        loadThumbnail = fileViewModel::loadThumbnail
                    )
                }
            }
        }
    }

    importProgress?.let {
        ByteProgressDialog("正在导入", Icons.Default.FileUpload, it, onCancel = { fileViewModel.cancelTransfer() })
    }
    fileExportProgress?.let {
        ByteProgressDialog("正在导出", Icons.Default.FileDownload, it, onCancel = { fileViewModel.cancelTransfer() })
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

    // 仅「进行中」显进度弹窗；完成态由上面的 LaunchedEffect 转 Snackbar。
    exportProgress?.takeIf { !it.finished }?.let {
        FolderExportDialog(it, onCancel = { fileViewModel.cancelTransfer() })
    }

    previewFile?.let { pf ->
        // 图片预览可在同文件夹图片间左右滑（siblings 仅图片）；视频/其它走单文件预览。
        val imageSiblings = if (pf.type == FileType.IMAGE) files.filter { it.type == FileType.IMAGE } else null
        FilePreviewDialog(file = pf, onClose = { previewFile = null }, siblings = imageSiblings)
    }

    // 导出目标弹框：确认「导出到手机」后才继续原流程（加密策略先收口令，否则直接启动系统选取器）。
    if (exportTargetFile != null || exportTargetAllFiles) {
        val f = exportTargetFile
        ExportTargetDialog(
            itemName = f?.name ?: folder?.name ?: "当前文件夹",
            isFolder = f == null,
            encrypted = encryptedPolicy,
            onExportToPhone = {
                exportTargetFile = null
                exportTargetAllFiles = false
                if (encryptedPolicy) {
                    // 先收口令，再选保存位置
                    if (f != null) passphraseForFile = f else passphraseForFolder = true
                } else {
                    pendingExportPass = null
                    if (f != null) {
                        fileToExport = f
                        exportLauncher.launch(f.name)
                    } else {
                        folderExportLauncher.launch(null)
                    }
                }
            },
            onDismiss = { exportTargetFile = null; exportTargetAllFiles = false }
        )
    }

    // 加密导出口令弹框（M12.5）：单文件或文件夹加密导出前收口令，确认后带口令启动选取器。
    if (passphraseForFile != null || passphraseForFolder) {
        val isFolder = passphraseForFolder
        ExportPassphraseDialog(
            isFolder = isFolder,
            onConfirm = { pass ->
                pendingExportPass = pass
                val f = passphraseForFile
                passphraseForFile = null
                passphraseForFolder = false
                if (f != null) {
                    fileToExport = f
                    exportLauncher.launch("${f.name}.${FileContainer.EXTENSION}")
                } else {
                    folderExportLauncher.launch(null)
                }
            },
            onDismiss = { passphraseForFile = null; passphraseForFolder = false }
        )
    }

    // 导入 .midun 容器（M12.5 解密侧）：选中容器 → 收一次导出口令 → 解回原文件落进本文件夹。
    pendingContainer?.let { pc ->
        ImportPassphraseDialog(
            fileName = pc.fileName,
            onConfirm = { pass ->
                fileViewModel.importContainer(folderId, pc.uri, pass)
                fileViewModel.clearPendingContainer()
            },
            onDismiss = { fileViewModel.clearPendingContainer() }
        )
    }
}

/**
 * 导出目标弹框。客户反馈：点「导出」直接跳系统文件选取器太突兀 → 先让用户明确点一次目标。
 * 目前只有「导出到手机」一个目标（点它才启动系统选取器挑保存位置）；[encrypted] 只影响文案，
 * 加密策略仍由调用方在本弹框之后接口令弹框。
 */
@Composable
private fun ExportTargetDialog(
    itemName: String,
    isFolder: Boolean,
    encrypted: Boolean,
    onExportToPhone: () -> Unit,
    onDismiss: () -> Unit
) {
    val what = if (isFolder) "文件夹" else "文件"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FileDownload, null, tint = Primary) },
        title = { Text("导出$what") },
        text = {
            Column {
                Text(
                    if (encrypted) "「$itemName」将加密成便携文件后保存到手机，之后需导出口令才能打开。"
                    else "「$itemName」将复制到手机存储，导出后不再受安全卡保护，请妥善保管。",
                    fontSize = 12.sp, color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onExportToPhone).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(Primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Smartphone, null, tint = Primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("导出到手机", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text("接下来选择手机中的保存位置", fontSize = 11.sp, color = TextSecondary)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

/**
 * 加密导出口令弹框（M12.5）。收一个导出口令（输入 + 确认两遍），确认后回调 [onConfirm]。
 * 诚实文案：口令是唯一钥匙、忘记无法找回、强度决定安全性——不吹「军工级」。
 */
@Composable
private fun ExportPassphraseDialog(isFolder: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val tooShort = pass.length < 4
    val mismatch = confirm.isNotEmpty() && pass != confirm
    val canConfirm = !tooShort && pass == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null, tint = Primary) },
        title = { Text(if (isFolder) "加密导出文件夹" else "加密导出文件") },
        text = {
            Column {
                Text(
                    "设置导出口令。文件将只可使用波波解密。" +
                        "请妥善记录口令密钥，忘记将无法找回。",
                    fontSize = 12.sp, color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("导出口令") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = pass.isNotEmpty() && tooShort,
                    supportingText = if (pass.isNotEmpty() && tooShort) { { Text("至少 4 位", color = Danger) } } else null,
                    trailingIcon = {
                        TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示", fontSize = 12.sp) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("再次输入口令") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text("两次口令不一致", color = Danger) } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (canConfirm) onConfirm(pass) },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

/**
 * 导入 .midun 容器的口令弹框（M12.5 解密侧）。只收**一个**口令（输入已有口令、非创建，故不二次确认）。
 * 口令是容器的唯一钥匙，跨设备一致；输错由解密时报「口令错误」。
 */
@Composable
private fun ImportPassphraseDialog(fileName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LockOpen, null, tint = Primary) },
        title = { Text("解密导入") },
        text = {
            Column {
                Text(
                    "「$fileName」是加密容器。输入导出时设置的口令，解密后将作为原文件导入本文件夹。",
                    fontSize = 12.sp, color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("导出口令") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示", fontSize = 12.sp) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (pass.isNotEmpty()) onConfirm(pass) },
                enabled = pass.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("解密导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

@Composable
private fun FileItemCard(
    file: FileItem,
    copyPolicy: CopyPolicy,
    moveTargets: List<FileItem>,
    onRename: (String) -> Unit,
    onMove: (FileItem) -> Unit,
    onExportFile: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    loadThumbnail: (suspend (FileItem) -> ImageBitmap?)? = null
) {
    // 音频加入可点开之列（客户需求 2026-08-12）：点开进 FilePreviewDialog 的播放器。
    // 缩略图仍只有图/视频有——音频没有画面可缩，列表里保持 AudioFile 图标（见下方 loadThumbnail 的判断）。
    val previewable = file.type == FileType.IMAGE || file.type == FileType.VIDEO || file.type == FileType.AUDIO
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
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

    Card(
        modifier = Modifier.fillMaxWidth().let { if (previewable) it.clickable(onClick = onPreview) else it },
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(iconData.second.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                // 图片/视频项显示卡内解密缩略图（客户反馈：原来只有通用图标，看不出内容）；视频缩略图右下角
                // 叠播放三角标示可播放。未就绪/失败退回通用图标。
                var thumb by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
                if (previewable && loadThumbnail != null) {
                    LaunchedEffect(file.id) { thumb = loadThumbnail(file) }
                }
                val tb = thumb
                if (tb != null) {
                    Image(tb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (file.type == FileType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                } else {
                    Icon(iconData.first, null, tint = iconData.second, modifier = Modifier.size(22.dp))
                }
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
                                if (moveTargets.isNotEmpty()) "移动到…" else "无其他文件夹",
                                color = if (moveTargets.isNotEmpty()) TextPrimary else TextSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DriveFileMove,
                                null,
                                tint = if (moveTargets.isNotEmpty()) Primary else TextSecondary
                            )
                        },
                        enabled = moveTargets.isNotEmpty(),
                        onClick = {
                            showMenu = false
                            showMoveDialog = true
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

    if (showMoveDialog) {
        MoveToFolderDialog(
            fileName = file.name,
            targets = moveTargets,
            onDismiss = { showMoveDialog = false },
            onSelect = { target ->
                onMove(target)
                showMoveDialog = false
            }
        )
    }
}

/** 选择目标隐私文件夹移动当前文件（M：文件移动增量）。列出当前文件夹之外的全部文件夹，点选即移动。 */
@Composable
private fun MoveToFolderDialog(
    fileName: String,
    targets: List<FileItem>,
    onDismiss: () -> Unit,
    onSelect: (FileItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileMove, null, tint = Primary) },
        title = { Text("移动到文件夹") },
        text = {
            Column {
                Text("将「$fileName」移动到：", fontSize = 14.sp, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                // **必须可滚**（客户 2026-08-14「移动到…未能显示全部文件夹」）：原来是一串 Row 直接堆在
                // AlertDialog 的 text 槽里，而 M3 的弹框会把内容高度卡在一个上限内，超出的部分就地裁掉
                // ——既滚不动也点不到，文件夹一多（每行约 56dp）后面的就凭空消失了。聊天那两个同类选取框
                // （保存到文件夹 / 从文件夹选文件）当初就是这么写的，本处是漏网的一个。
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    targets.forEach { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(target) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                    .background(Primary.copy(0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(target.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(target.copyPolicy.label(), fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
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
                title = { Text("新建文件夹") },
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
            text = { Text("文件夹「$folderName」已在设备中创建") },
            confirmButton = {
                Button(
                    onClick = { showSuccess = false; onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("确定") }
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
