package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.midun.data.model.UsbDeviceStatus
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.DeviceViewModel

/**
 * 驱动模式测试入口：一个三点按钮 + 「连接模式」面板。挂在加载页（Splash）和登录页——
 * 鸿蒙 2.0 上卡要连好几分钟，测试人员在「正在检测设备…」这一屏就能直接换模式，不用等进登录页。
 * 诊断脚手架，模式定下来后连同 SettingsStore/RealUsbManager 的接线整体移除。
 */
@Composable
fun DriverModeEntry(
    deviceViewModel: DeviceViewModel,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    var showPanel by remember { mutableStateOf(false) }
    val driverMode by deviceViewModel.driverMode.collectAsState()
    val perfLog by deviceViewModel.perfLog.collectAsState()
    val devStatus by deviceViewModel.deviceStatus.collectAsState()

    IconButton(onClick = { showPanel = true }, modifier = modifier) {
        Icon(Icons.Default.MoreVert, contentDescription = "连接模式", tint = tint)
    }

    if (showPanel) {
        DriverModePanel(
            currentMode = driverMode,
            statusText = when (devStatus.status) {
                UsbDeviceStatus.AUTHENTICATED, UsbDeviceStatus.CONNECTED -> "已连接"
                UsbDeviceStatus.CONNECTING -> "连接中…"
                else -> "未连接"
            },
            perfLog = perfLog,
            onSelect = { deviceViewModel.switchDriverMode(it) },
            onDismiss = { showPanel = false }
        )
    }
}

/**
 * 驱动模式面板：切 0/2 即持久化 + 重连，实时显示连接状态与卡层耗时，
 * 供测试人员在鸿蒙 2.0 上对比登录快慢。文案极简（测试人员不懂技术）。
 */
@Composable
private fun DriverModePanel(
    currentMode: Int,
    statusText: String,
    perfLog: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(20.dp)
        ) {
            Column {
                Text("连接模式", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("切换后自动重连，看下方耗时对比快慢", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeChoice("模式 0", currentMode == 0, Modifier.weight(1f)) { onSelect(0) }
                    ModeChoice("模式 2", currentMode == 2, Modifier.weight(1f)) { onSelect(2) }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("状态：", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (statusText == "已连接") Success else Warning
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("连接耗时", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        perfLog.ifBlank { "（暂无，插卡或切换后出现）" },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = Primary)
                }
            }
        }
    }
}

/** 驱动模式面板里的一个模式选项块（选中高亮）。 */
@Composable
private fun ModeChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.5.dp,
                if (selected) Primary else TextSecondary.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp)
            )
            .background(if (selected) Primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Primary else TextSecondary
        )
    }
}
