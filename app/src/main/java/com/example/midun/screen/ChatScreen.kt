package com.example.midun.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.example.midun.data.model.Contact
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatListScreen(
    onContactClick: (String) -> Unit,
    onQrCodeClick: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val contacts by chatViewModel.contacts.collectAsState()
    val filteredContacts by chatViewModel.filteredContacts.collectAsState()
    // 本地同步状态承载输入框显示值，避免 value 经 ViewModel StateFlow 异步往返而打断
    // 中文/IME 的组合（composition）会话；变化转发给 ViewModel 仅用于驱动 filteredContacts 过滤。
    var searchText by rememberSaveable { mutableStateOf("") }
    var contactToDelete by remember { mutableStateOf<Contact?>(null) }

    // 进屏重读单例：扫码建联 / 清空会话后回到列表能反映改动（见 M6.1 偏离）。
    LaunchedEffect(Unit) {
        chatViewModel.loadContacts()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Chat, null, tint = Primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("相逢叙话", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onQrCodeClick,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.15f))
            ) {
                Icon(Icons.Default.QrCode2, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("同波相契", color = Accent, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("端到端加密 · 无服务器中转 · 匿名通信", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(12.dp))

        if (contacts.isNotEmpty()) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it; chatViewModel.updateSearchQuery(it) },
                placeholder = { Text("搜索联系人...", color = TextSecondary.copy(alpha = 0.6f)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = ""; chatViewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, null, tint = TextSecondary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f)
                )
            )
            Spacer(Modifier.height(12.dp))
        }

        when {
            contacts.isEmpty() -> EmptyContactsState(onQrCodeClick = onQrCodeClick)
            filteredContacts.isEmpty() -> NoSearchResultState(query = searchText)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filteredContacts, key = { it.id }) { contact ->
                    SwipeableContactItem(
                        contact = contact,
                        onOpenChat = { onContactClick(contact.id) },
                        onPin = { chatViewModel.togglePin(contact.id) },
                        onDelete = { contactToDelete = contact }
                    )
                }
            }
        }
    }

    contactToDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("清空聊天记录") },
            text = { Text("将删除与「${contact.remark}」的所有聊天记录，且无法恢复。", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.clearAllMessages(contact.id)
                        contactToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun NoSearchResultState(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.SearchOff,
                null,
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("未找到匹配「$query」的联系人", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyContactsState(onQrCodeClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                null,
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("暂无联系人", color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onQrCodeClick,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
            ) {
                Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("发起连接")
            }
        }
    }
}

/**
 * 联系人条目 + 左滑显示「置顶 / 删除」操作块（微信式，右侧锚定）。
 * 用 Animatable 偏移前景卡片，detectHorizontalDragGestures 拖动，松手按半程阈值吸附到开/合。
 */
@Composable
private fun SwipeableContactItem(
    contact: Contact,
    onOpenChat: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val actionWidth = 76.dp
    val revealPx = with(density) { (actionWidth * 2).toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember(contact.id) { Animatable(0f) }

    // 外层裁成与卡片相同的圆角：否则圆角缺口处会露出背景方形操作块的方角。
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
        // 背景操作块：贴右排列，随前景卡片左滑而露出。
        Row(modifier = Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            SwipeActionBlock(
                if (contact.isPinned) "取消置顶" else "置顶",
                Icons.Default.PushPin, Primary, actionWidth
            ) {
                onPin(); scope.launch { offsetX.animateTo(0f) }
            }
            SwipeActionBlock("删除", Icons.Default.Delete, Danger, actionWidth) {
                scope.launch { offsetX.animateTo(0f) }; onDelete()
            }
        }
        // 前景卡片：水平拖动改变偏移，松手吸附。
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(contact.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + drag).coerceIn(-revealPx, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                offsetX.animateTo(if (offsetX.value < -revealPx / 2f) -revealPx else 0f)
                            }
                        }
                    )
                }
        ) {
            ContactItem(
                contact = contact,
                onClick = {
                    // 打开状态下点击先收回，否则进入会话。
                    if (offsetX.value != 0f) scope.launch { offsetX.animateTo(0f) } else onOpenChat()
                }
            )
        }
    }
}

@Composable
private fun SwipeActionBlock(
    label: String,
    icon: ImageVector,
    color: Color,
    width: Dp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(color)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun ContactItem(contact: Contact, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        // 置顶项浅灰底以区分（CardBg=白 / Surface=浅灰）。
        colors = CardDefaults.cardColors(
            containerColor = if (contact.isPinned) Surface else CardBg
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.remark.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.remark, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (contact.isOnline) {
                        Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Success))
                    }
                    if (contact.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.PushPin, "已置顶", tint = Primary, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    contact.lastMessage,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTime(contact.lastMessageTime), fontSize = 11.sp, color = TextSecondary)
                if (contact.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(Danger),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${contact.unreadCount}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** 联系人列表的相对时间：今日→HH:mm，昨日→昨天，本周→周X，更早→MM-dd（跨年→yyyy-MM-dd）。0L→空串。 */
private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1
    val withinWeek = sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) in 2..6

    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
        yesterday -> "昨天"
        withinWeek -> arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[then.get(Calendar.DAY_OF_WEEK) - 1]
        sameYear -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(timestamp))
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))
    }
}
