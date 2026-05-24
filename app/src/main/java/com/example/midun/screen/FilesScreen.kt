package com.example.midun.screen

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
                    FolderCard(folder, onClick = { onFolderClick(folder.id) })
                }
            }
        }
    }
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
private fun FolderCard(folder: FileItem, onClick: () -> Unit) {
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
            Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
        }
    }
}

private fun CopyPolicy.label(): String =
    when (this) {
        CopyPolicy.NO_COPY -> "不可拷贝"
        CopyPolicy.COPY_PLAIN -> "拷贝明文"
        CopyPolicy.COPY_ENCRYPTED -> "拷贝密文"
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
    val folder = uiState.folders.find { it.id == folderId }
    val files = if (uiState.currentFolderId == folderId) uiState.currentFiles else emptyList()
    var showMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

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
                        DropdownMenuItem(text = { Text("导出文件") }, onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) })
                        DropdownMenuItem(text = { Text("全部删除") }, onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = Danger) })
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
                    FileItemCard(file)
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
                        onClick = { showImportDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null)
                        Spacer(Modifier.width(8.dp))
                        Text("从手机存储导入")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showImportDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Usb, null)
                        Spacer(Modifier.width(8.dp))
                        Text("从普通U盘导入")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FileItemCard(file: FileItem) {
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
            Icon(Icons.Default.MoreVert, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFolderScreen(onBack: () -> Unit) {
    var folderName by remember { mutableStateOf("") }
    var copyMode by remember { mutableIntStateOf(0) } // 0=不可拷贝 1=拷贝明文 2=拷贝密文
    var showSuccess by remember { mutableStateOf(false) }

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
                onClick = { showSuccess = true },
                enabled = folderName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Default.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("创建文件夹")
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
