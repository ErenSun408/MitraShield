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
import com.example.midun.data.MockData
import com.example.midun.ui.theme.*

@Composable
fun ChatListScreen(
    onContactClick: (String) -> Unit,
    onQrCodeClick: () -> Unit
) {
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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(MockData.contacts) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onContactClick(contact.id) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                contact.name.first().toString(),
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.VerifiedUser, null, tint = Accent, modifier = Modifier.size(14.dp))
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
                            Text(contact.lastTime, fontSize = 11.sp, color = TextSecondary)
                            if (contact.unreadCount > 0) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Danger),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${contact.unreadCount}", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}