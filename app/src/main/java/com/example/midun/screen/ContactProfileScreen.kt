package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel

/**
 * 联系人资料页（v4 外增量，2026-06-09）：由 ChatDetailScreen 点顶栏标题进入。
 * 展示/编辑备注，提供「删除联系人」。
 * 叶子屏幕用回调（onBack / onContactDeleted），不持 navController（全局约定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    contactId: String,
    onBack: () -> Unit,
    onContactDeleted: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val contacts by chatViewModel.contacts.collectAsState()
    val contact = contacts.find { it.id == contactId }

    // 本屏是独立 NavBackStackEntry → 独立 ChatViewModel 实例（同 M5.3 偏离），进屏重读单例联系人。
    LaunchedEffect(contactId) { chatViewModel.loadContacts() }

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("联系人信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Surface
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            // 头像：备注首字符（无备注用「?」）。
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape).background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact?.remark?.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                contact?.remark ?: "未知联系人",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Circle, null,
                    tint = if (contact?.isOnline == true) Success else TextSecondary,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (contact?.isOnline == true) "在线" else "离线",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.height(24.dp))

            // 信息卡：备注（可编辑）。
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column {
                    InfoEditRow(
                        label = "备注名称",
                        value = contact?.remark ?: "",
                        onClick = { showEditDialog = true }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 危险操作：删除联系人。
            Button(
                onClick = { showDeleteConfirm = true },
                enabled = !deleting && contact != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) {
                Icon(Icons.Default.DeleteForever, null)
                Spacer(Modifier.width(8.dp))
                Text("删除联系人")
            }
        }
    }

    if (showEditDialog && contact != null) {
        var remark by remember { mutableStateOf(contact.remark) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            icon = { Icon(Icons.Default.Edit, null, tint = Primary) },
            title = { Text("修改备注") },
            text = {
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.updateRemark(contactId, remark.trim())
                        showEditDialog = false
                    },
                    enabled = remark.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("删除联系人") },
            text = {
                Text(
                    "将删除该联系人及与其的全部聊天记录，且无法恢复。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 删完才导航：onComplete 里 onContactDeleted，避免提前 pop 销毁 scope 中断删除。
                        deleting = true
                        chatViewModel.deleteContact(contactId, onComplete = onContactDeleted)
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    if (deleting) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("删除中…")
                    } else {
                        Text("确认删除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }, enabled = !deleting) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

/** 可编辑信息行：右侧带编辑图标，整行可点。 */
@Composable
private fun InfoEditRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.Edit, "编辑", tint = Primary, modifier = Modifier.size(18.dp))
    }
}
