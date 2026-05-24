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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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

    // 进屏重读单例：扫码建联 / 清空会话后回到列表能反映改动（见 M6.1 偏离）。
    LaunchedEffect(Unit) {
        chatViewModel.loadContacts()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Chat, null, tint = Primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("即时通信", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onQrCodeClick,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.15f))
            ) {
                Icon(Icons.Default.QrCode2, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("扫码建链", color = Accent, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("端到端加密 · 无服务器中转 · 匿名通信", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(16.dp))

        if (contacts.isEmpty()) {
            EmptyContactsState(onQrCodeClick = onQrCodeClick)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(contacts, key = { it.id }) { contact ->
                    ContactItem(contact = contact, onClick = { onContactClick(contact.id) })
                }
            }
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

@Composable
private fun ContactItem(contact: Contact, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
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
