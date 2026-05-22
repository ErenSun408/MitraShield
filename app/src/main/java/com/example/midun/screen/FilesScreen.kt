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
import com.example.midun.data.*
import com.example.midun.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit
) {
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

            items(MockData.folders) { folder ->
                FolderCard(folder, onClick = { onFolderClick(folder.id) })
            }
        }
    }
}

@Composable
private fun FolderCard(folder: SecureFolder, onClick: () -> Unit) {
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
                    Text("${folder.fileCount}个文件", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(folder.createdTime, fontSize = 12.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (folder.allowCopy) Accent.copy(0.15f) else Danger.copy(0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            folder.copyMode,
                            fontSize = 10.sp,
                            color = if (folder.allowCopy) Accent else Danger
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(folderId: String, onBack: () -> Unit) {
    val folder = MockData.folders.find { it.id == folderId }
    val files = MockData.files[folderId] ?: emptyList()
    var showMenu by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder?.name ?: "文件夹") },
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
                                Text(folder?.copyMode ?: "-", fontWeight = FontWeight.Bold, color = if (folder?.allowCopy == true) Accent else Danger)
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

                items(files) { file ->
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
private fun FileItemCard(file: SecureFile) {
    val iconData = when (file.type) {
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
                    Text(file.size, fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(file.createdTime, fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(file.source, fontSize = 11.sp, color = Accent)
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