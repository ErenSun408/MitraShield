package com.example.midun.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.BuildConfig
import com.example.midun.data.SettingsStore
import com.example.midun.ui.theme.*
import com.example.midun.util.formatStorage
import com.example.midun.util.formatStorageCoarse
import com.example.midun.viewmodel.DeviceViewModel

/**
 * 「文件加密密钥更新」入口开关（客户 2026-07-27 要求对用户隐藏）。功能本身、弹框与 [DeviceViewModel.updateKey]
 * 原样保留，置 true 即恢复入口——同 LoginScreen 的 `SHOW_FORGOT_PASSWORD` 写法。
 */
private const val SHOW_KEY_UPDATE = false

/**
 * 设置 Tab。
 *
 * 导航约定（与全局回调风格一致，不持 navController）：
 * - [onLogoutComplete]：本屏调 [DeviceViewModel.logout] 之后由 NavGraph 决定去向（当前接 Splash → 自然路由到 Login）。
 * - [onFactoryResetComplete]：M7.2 的"恢复出厂"成功后由 NavGraph 接 `navigate(Init){popUpTo(0)}`，本屏只负责回调。
 *
 * M7.1 范围：骨架重写 + 设备信息真值 + 退出登录。其余动作按钮（密钥更新/绑定/自动锁定/一键清理/恢复出厂）为占位，
 * 接线在 M7.2 ~ M7.5 完成。
 */
@Composable
fun SettingsScreen(
    onLogoutComplete: () -> Unit,
    onFactoryResetComplete: () -> Unit,
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    val deviceStatus by deviceViewModel.deviceStatus.collectAsState()

    // 一键清理 / 恢复出厂：M7.2 带密码确认的危险操作弹框。
    var showCleanDialog by remember { mutableStateOf(false) }
    var cleanPassword by remember { mutableStateOf("") }
    var cleanError by remember { mutableStateOf("") }
    var cleanLoading by remember { mutableStateOf(false) }

    var showResetDialog by remember { mutableStateOf(false) }
    var resetPassword by remember { mutableStateOf("") }
    var resetError by remember { mutableStateOf("") }
    var resetLoading by remember { mutableStateOf(false) }

    // 绑定管理（M7.3 patch §M7 改动2）：bindAction 在打开弹框时按当前绑定态捕获，避免成功后状态翻转导致弹框文案抖动。
    var showBindDialog by remember { mutableStateOf(false) }
    var bindAction by remember { mutableStateOf(false) } // true = bind, false = unbind
    var bindPassword by remember { mutableStateOf("") }
    var bindError by remember { mutableStateOf("") }
    var bindLoading by remember { mutableStateOf(false) }

    // 密钥更新（M7.4 patch §M7 改动3）：弹框含两态——输入密码态 vs 成功态。
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyPassword by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf("") }
    var keyLoading by remember { mutableStateOf(false) }
    var keySuccess by remember { mutableStateOf(false) }

    // 自动锁定（M7.5）：当前值来自 VM；picker 内的暂选值在 dialog 打开期间存活，确定才写回 VM。
    val timeoutMin by deviceViewModel.inactivityTimeoutMinutes.collectAsState()
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var pickedTimeout by remember { mutableIntStateOf(timeoutMin) }

    // 息屏自动退出（关闭 / 立即 / 10 秒）：与自动锁定同款滚轮 picker，暂选值确定才写回 VM。
    val screenOffSec by deviceViewModel.screenOffExitSeconds.collectAsState()
    var showScreenOffDialog by remember { mutableStateOf(false) }
    var pickedScreenOff by remember { mutableIntStateOf(screenOffSec) }

    // 最近操作开关（默认关）：首页据此显示/隐藏「最近操作」区块，仓库据此决定记不记。
    val operationLogEnabled by deviceViewModel.operationLogEnabled.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }

    val isBound = deviceStatus.boundPhoneId != null

    // 退出自动清理（下次登录补清）：开启需密码确认。pendingExitClearTarget = "contacts"/"files" 表示正开启哪项。
    val exitClearPrefs by deviceViewModel.exitClearPrefs.collectAsState()
    var pendingExitClearTarget by remember { mutableStateOf<String?>(null) }
    var exitClearPassword by remember { mutableStateOf("") }
    var exitClearError by remember { mutableStateOf("") }
    var exitClearLoading by remember { mutableStateOf(false) }

    // 文件预览缓存（file-transfer 阶段4）：副标题显占用，点击确认后只清 .recv_/.sent_ 暂存。
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var showCacheDialog by remember { mutableStateOf(false) }
    fun refreshCache() = deviceViewModel.loadCacheStats { _, b -> cacheBytes = b }
    LaunchedEffect(Unit) { refreshCache() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ── 设备信息 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("设备信息", color = Primary)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Primary.copy(0.05f))
        ) {
            Column(Modifier.padding(16.dp)) {
                SettingsInfoItem(
                    "绑定状态",
                    if (deviceStatus.boundPhoneId != null) "已绑定本机" else "未绑定"
                )
                // 真卡容量（M11.6.1，SFGetCapacity）；未认证 totalBytes=0 → 显占位。
                val total = deviceStatus.totalBytes
                val used = (total - deviceStatus.freeBytes).coerceAtLeast(0L)
                SettingsInfoItem(
                    "存储使用",
                    if (total > 0) "${formatStorageCoarse(used)} / ${formatStorage(total)}" else "-- / --"
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 安全操作 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("安全操作")
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                if (SHOW_KEY_UPDATE) {
                    SettingsActionItem(
                        icon = Icons.Default.Key,
                        title = "文件加密密钥更新",
                        subtitle = "替换文件加密的二级密钥",
                        iconTint = Accent,
                        onClick = { showKeyDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                SettingsActionItem(
                    icon = if (isBound) Icons.Default.PhonelinkErase else Icons.Default.PhonelinkSetup,
                    title = if (isBound) "解绑本机" else "绑定本机",
                    subtitle = if (isBound) "解绑后设备可在其他手机使用" else "绑定后设备只能在此手机使用",
                    iconTint = if (isBound) Warning else Accent,
                    onClick = {
                        bindAction = !isBound
                        showBindDialog = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.Timer,
                    title = "自动锁定",
                    subtitle = "后台无操作 $timeoutMin 分钟后自动退出",
                    iconTint = Accent,
                    trailing = {
                        Text(
                            "$timeoutMin 分钟",
                            color = Primary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        pickedTimeout = timeoutMin
                        showTimeoutDialog = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.MobileOff,
                    title = "息屏退出",
                    subtitle = "息屏后自动退出应用",
                    iconTint = Accent,
                    trailing = {
                        Text(
                            screenOffExitLabel(screenOffSec),
                            color = Primary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        pickedScreenOff = screenOffSec
                        showScreenOffDialog = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.CleaningServices,
                    title = "清除缓存",
                    subtitle = "聊天图片/视频预览缓存" +
                        (cacheBytes?.let { "（占用 ${if (it <= 0L) "0 KB" else formatStorage(it)}）" } ?: ""),
                    iconTint = Accent,
                    onClick = { showCacheDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                // 最近操作（默认关，客户 2026-07-28）：关着时首页不显示该区块、后台也不记录；
                // 关闭动作会一并清掉已记录的条目，故再次开启是从零开始。
                SettingsActionItem(
                    icon = Icons.Default.History,
                    title = "最近操作",
                    subtitle = "开启后在首页显示，并从开启时开始记录",
                    iconTint = Accent,
                    trailing = {
                        Switch(
                            checked = operationLogEnabled,
                            onCheckedChange = { deviceViewModel.setOperationLogEnabled(it) }
                        )
                    },
                    onClick = { deviceViewModel.setOperationLogEnabled(!operationLogEnabled) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 自动清理（下次登录补清）──────────────────────────────────────────────────
        SettingsSectionHeader("自动清理")
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                SettingsActionItem(
                    icon = Icons.Default.PersonRemove,
                    title = "退出时清空联系人",
                    subtitle = "开启后每次退出自动清空所有联系人与聊天记录，并清空最近操作",
                    iconTint = Warning,
                    trailing = {
                        Switch(
                            checked = exitClearPrefs.clearContacts,
                            onCheckedChange = { on ->
                                if (on) {
                                    pendingExitClearTarget = "contacts"; exitClearPassword = ""; exitClearError = ""
                                } else deviceViewModel.setClearContactsOnExit(false, null, {}, {})
                            }
                        )
                    },
                    onClick = {
                        if (!exitClearPrefs.clearContacts) {
                            pendingExitClearTarget = "contacts"; exitClearPassword = ""; exitClearError = ""
                        } else deviceViewModel.setClearContactsOnExit(false, null, {}, {})
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.FolderDelete,
                    title = "退出时文件不保留",
                    subtitle = "开启后每次退出自动清空文件及行为",
                    iconTint = Warning,
                    trailing = {
                        Switch(
                            checked = exitClearPrefs.clearFiles,
                            onCheckedChange = { on ->
                                if (on) {
                                    pendingExitClearTarget = "files"; exitClearPassword = ""; exitClearError = ""
                                } else deviceViewModel.setClearFilesOnExit(false, null, {}, {})
                            }
                        )
                    },
                    onClick = {
                        if (!exitClearPrefs.clearFiles) {
                            pendingExitClearTarget = "files"; exitClearPassword = ""; exitClearError = ""
                        } else deviceViewModel.setClearFilesOnExit(false, null, {}, {})
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 危险操作 ──────────────────────────────────────────────────────────────
        SettingsSectionHeader("危险操作", color = Danger)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column {
                SettingsActionItem(
                    icon = Icons.Default.DeleteForever,
                    title = "涤净闲存",
                    iconTint = Danger,
                    onClick = { showCleanDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionItem(
                    icon = Icons.Default.RestartAlt,
                    title = "恢复初始状态",
                    iconTint = Danger,
                    onClick = { showResetDialog = true }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 退出登录：底部红色按钮，与上方卡片留出明显间距 ─────────────────────────
        Button(
            onClick = {
                deviceViewModel.logout()
                onLogoutComplete()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Danger)
        ) {
            Icon(Icons.Default.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("退出登录", fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // ── 关于：最底部小字链接 ──────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { showAboutDialog = true }) {
                Text(
                    "关于波波 v${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showCleanDialog) {
        val dismiss = {
            showCleanDialog = false
            cleanPassword = ""
            cleanError = ""
            cleanLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!cleanLoading) dismiss() },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("涤净闲存") },
            text = {
                Column {
                    Text(
                        "将清除设备中的所有信息。\n\n此操作不可恢复，请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cleanPassword,
                        onValueChange = { cleanPassword = it; cleanError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        isError = cleanError.isNotEmpty(),
                        supportingText = {
                            if (cleanError.isNotEmpty()) Text(cleanError, color = Danger)
                        },
                        enabled = !cleanLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        cleanLoading = true
                        cleanError = ""
                        deviceViewModel.wipeUserData(
                            password = cleanPassword,
                            onSuccess = { dismiss() },
                            onError = { msg ->
                                cleanError = msg
                                cleanLoading = false
                            }
                        )
                    },
                    enabled = cleanPassword.isNotBlank() && !cleanLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    if (cleanLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("清理中…")
                    } else {
                        Text("确认清除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !cleanLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    pendingExitClearTarget?.let { target ->
        val isContacts = target == "contacts"
        val dismiss = {
            pendingExitClearTarget = null
            exitClearPassword = ""
            exitClearError = ""
            exitClearLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!exitClearLoading) dismiss() },
            icon = { Icon(Icons.Default.Warning, null, tint = Warning) },
            title = { Text(if (isContacts) "退出时清空联系人" else "退出时文件不保留") },
            text = {
                Column {
                    Text(
                        if (isContacts)
                            "开启后，每次退出时将自动清空所有联系人与聊天记录，并清空最近操作，且不可恢复。请谨慎开启。\n\n请输入当前密码确认："
                        else
                            "开启后，每次退出时将自动清空文件及行为，且不可恢复。请谨慎开启。\n\n请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exitClearPassword,
                        onValueChange = { exitClearPassword = it; exitClearError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        isError = exitClearError.isNotEmpty(),
                        supportingText = { if (exitClearError.isNotEmpty()) Text(exitClearError, color = Danger) },
                        enabled = !exitClearLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        exitClearLoading = true
                        exitClearError = ""
                        val onOk = { dismiss() }
                        val onErr: (String) -> Unit = { m -> exitClearError = m; exitClearLoading = false }
                        if (isContacts) deviceViewModel.setClearContactsOnExit(true, exitClearPassword, onOk, onErr)
                        else deviceViewModel.setClearFilesOnExit(true, exitClearPassword, onOk, onErr)
                    },
                    enabled = exitClearPassword.isNotBlank() && !exitClearLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                ) {
                    Text("确认开启")
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !exitClearLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showResetDialog) {
        val dismiss = {
            showResetDialog = false
            resetPassword = ""
            resetError = ""
            resetLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!resetLoading) dismiss() },
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("恢复初始状态", color = Danger) },
            text = {
                Column {
                    Text(
                        "此操作将永久删除设备内所有文件、聊天记录和密码，且无法恢复。\n\n请输入当前密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetPassword,
                        onValueChange = { resetPassword = it; resetError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        isError = resetError.isNotEmpty(),
                        supportingText = {
                            if (resetError.isNotEmpty()) Text(resetError, color = Danger)
                        },
                        enabled = !resetLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetLoading = true
                        resetError = ""
                        deviceViewModel.factoryReset(
                            password = resetPassword,
                            onSuccess = {
                                // 顺序与 [DeviceViewModel.wipeAndReset] / M3.5 一致：
                                // wipeAll 已完成才回调；这里再触发导航，避免本 VM 在 wipeAll 中途被 popUpTo(0) 取消。
                                dismiss()
                                onFactoryResetComplete()
                            },
                            onError = { msg ->
                                resetError = msg
                                resetLoading = false
                            }
                        )
                    },
                    enabled = resetPassword.isNotBlank() && !resetLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    if (resetLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("重置中…")
                    } else {
                        Text("确认重置")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !resetLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showBindDialog) {
        val dismiss = {
            showBindDialog = false
            bindPassword = ""
            bindError = ""
            bindLoading = false
        }
        AlertDialog(
            onDismissRequest = { if (!bindLoading) dismiss() },
            icon = {
                Icon(
                    if (bindAction) Icons.Default.PhonelinkSetup else Icons.Default.PhonelinkErase,
                    null,
                    tint = if (bindAction) Accent else Warning
                )
            },
            title = { Text(if (bindAction) "绑定本机" else "解绑本机") },
            text = {
                Column {
                    Text(
                        if (bindAction) "绑定后设备只能在此手机上使用，请输入密码确认："
                        else "解绑后设备可在任意手机使用，请输入密码确认：",
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bindPassword,
                        onValueChange = { bindPassword = it; bindError = "" },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        isError = bindError.isNotEmpty(),
                        supportingText = {
                            if (bindError.isNotEmpty()) Text(bindError, color = Danger)
                        },
                        enabled = !bindLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        bindLoading = true
                        bindError = ""
                        deviceViewModel.updateBinding(
                            password = bindPassword,
                            bind = bindAction,
                            onSuccess = { dismiss() },
                            onError = { msg ->
                                bindError = msg
                                bindLoading = false
                            }
                        )
                    },
                    enabled = bindPassword.isNotBlank() && !bindLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bindAction) Accent else Warning
                    )
                ) {
                    if (bindLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("处理中…")
                    } else {
                        Text("确认")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !bindLoading) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showKeyDialog) {
        val dismiss = {
            showKeyDialog = false
            keyPassword = ""
            keyError = ""
            keyLoading = false
            keySuccess = false
        }
        AlertDialog(
            onDismissRequest = { if (!keyLoading) dismiss() },
            icon = {
                Icon(
                    if (keySuccess) Icons.Default.CheckCircle else Icons.Default.Key,
                    null,
                    tint = if (keySuccess) Success else Accent
                )
            },
            title = { Text(if (keySuccess) "文件加密密钥已更新" else "文件加密密钥更新") },
            text = {
                if (keySuccess) {
                    Text(
                        "已重新生成文件封装密钥。已储存的文件无需重新加密。",
                        color = TextSecondary
                    )
                } else {
                    Column {
                        Text(
                            "将重新生成文件封装密钥，并用它重新封装现有的文件加密密钥。\n\n" +
                                "• 文件加密密钥不变，文件夹文件无需重新加密、不会丢失\n" +
                                "• 此操作不可撤销\n\n" +
                                "请输入密码确认：",
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = keyPassword,
                            onValueChange = { keyPassword = it; keyError = "" },
                            label = { Text("当前密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            // 用默认输入法（不设 Password 类型，避免第三方输入法回退到系统自带键盘）；
                        // 打码靠 PasswordVisualTransformation，无痕靠窗口 FLAG_SECURE。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            isError = keyError.isNotEmpty(),
                            supportingText = {
                                if (keyError.isNotEmpty()) Text(keyError, color = Danger)
                            },
                            enabled = !keyLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (keySuccess) {
                    Button(
                        onClick = dismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Success)
                    ) { Text("完成") }
                } else {
                    Button(
                        onClick = {
                            keyLoading = true
                            keyError = ""
                            deviceViewModel.updateKey(
                                password = keyPassword,
                                onSuccess = {
                                    keyLoading = false
                                    keyPassword = ""
                                    keySuccess = true
                                },
                                onError = { msg ->
                                    keyError = msg
                                    keyLoading = false
                                }
                            )
                        },
                        enabled = keyPassword.isNotBlank() && !keyLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        if (keyLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("更新中…")
                        } else {
                            Text("确认更新")
                        }
                    }
                }
            },
            dismissButton = {
                if (!keySuccess) {
                    TextButton(onClick = dismiss, enabled = !keyLoading) {
                        Text("取消", color = TextSecondary)
                    }
                }
            }
        )
    }

    if (showTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            icon = { Icon(Icons.Default.Timer, null, tint = Accent) },
            title = { Text("自动锁定") },
            text = {
                Column {
                    Text(
                        "请选择后台无操作多久后自动退出登录。",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    WheelTimePicker(
                        options = TIMEOUT_OPTIONS_MIN,
                        initialValue = pickedTimeout,
                        onSelectionChanged = { pickedTimeout = it }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deviceViewModel.setInactivityTimeoutMinutes(pickedTimeout)
                        showTimeoutDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimeoutDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showScreenOffDialog) {
        AlertDialog(
            onDismissRequest = { showScreenOffDialog = false },
            icon = { Icon(Icons.Default.MobileOff, null, tint = Accent) },
            title = { Text("息屏退出") },
            text = {
                Column {
                    Text(
                        "请选择息屏多久后自动退出应用",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    WheelTimePicker(
                        options = SCREEN_OFF_OPTIONS,
                        initialValue = pickedScreenOff,
                        onSelectionChanged = { pickedScreenOff = it },
                        label = ::screenOffExitLabel
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deviceViewModel.setScreenOffExitSeconds(pickedScreenOff)
                        showScreenOffDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showScreenOffDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Security, null, tint = Primary) },
            title = { Text("波波 v${BuildConfig.VERSION_NAME}") },
            text = {
                Column {
                    Text("设备管理系统")
                    Spacer(Modifier.height(8.dp))
                    Text("功能特性：", fontWeight = FontWeight.Medium)
                    Text("  - 文件加密存储")
                    Text("  - 端到端加密通信")
                    Text("  - 密码安全保护")
                    Text("  - T620安全芯片")
                    Spacer(Modifier.height(8.dp))
                    Text("支持系统：Android 8.0+ / 鸿蒙4.0", fontSize = 12.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("确定") }
            }
        )
    }

    // 清除文件缓存确认（file-transfer 阶段4）：只清 .recv_/.sent_ 暂存，已保存到文件夹的不受影响。
    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            icon = { Icon(Icons.Default.CleaningServices, null, tint = Accent) },
            title = { Text("清除文件缓存") },
            text = {
                Text(
                    "将清除聊天中图片/视频的预览缓存" +
                        (cacheBytes?.takeIf { it > 0 }?.let { "（约 ${formatStorage(it)}）" } ?: "") +
                        "。\n\n对话中未保存的文件可能无法再预览；已保存到文件夹的文件不受影响。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCacheDialog = false
                    deviceViewModel.clearFileCache { freed ->
                        refreshCache()
                        Toast.makeText(
                            context,
                            if (freed > 0) "已清除 ${formatStorage(freed)} 缓存" else "没有可清除的缓存",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("清除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showCacheDialog = false }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

/** 分区标题。颜色默认用 TextSecondary，分区为危险/主色时按需覆盖。 */
@Composable
private fun SettingsSectionHeader(title: String, color: Color = TextSecondary) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 设备信息只读行：标签 + 值。 */
@Composable
private fun SettingsInfoItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 自动锁定可选时长（分钟）。位置居中、首尾留余更利于 wheel 滚动手感。 */
private val TIMEOUT_OPTIONS_MIN = listOf(1, 3, 5, 10, 15, 30, 60)

/** 息屏自动退出可选档：SettingsStore 秒数约定（-1 关闭 / 0 立即 / 10 秒），显示文案见 [screenOffExitLabel]。 */
private val SCREEN_OFF_OPTIONS = listOf(
    SettingsStore.SCREEN_OFF_EXIT_OFF,
    SettingsStore.SCREEN_OFF_EXIT_IMMEDIATE,
    10
)

/** 息屏退出档位的行尾简短标签。 */
private fun screenOffExitLabel(seconds: Int): String = when (seconds) {
    SettingsStore.SCREEN_OFF_EXIT_OFF -> "关闭"
    SettingsStore.SCREEN_OFF_EXIT_IMMEDIATE -> "立即"
    else -> "$seconds 秒"
}

/**
 * 滚轮时长选择：LazyColumn + rememberSnapFlingBehavior，中间一格为选中态。
 * - 初次进入按 [initialValue] 滚到对应项。
 * - 滚停后 firstVisibleItemIndex 即中心项（因 contentPadding=itemHeight、可视 3 项）。
 * - 选择变化通过 [onSelectionChanged] 回调实时上抛，父层只在"确定"时提交到 VM。
 */
@Composable
private fun WheelTimePicker(
    options: List<Int>,
    initialValue: Int,
    onSelectionChanged: (Int) -> Unit,
    label: (Int) -> String = { "$it 分钟" }
) {
    val itemHeight = 48.dp
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState)

    LaunchedEffect(Unit) {
        val idx = options.indexOf(initialValue).coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    // 中心项 = 中点离视口中线最近的可见项。不用 firstVisibleItemIndex：它到不了最后一项
    // （末项下方内容不足 3 格视口高、列表滚到底前就封顶在倒数第二项 → 末项永远选不中）。
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - mid) }
                ?.index?.coerceIn(0, options.size - 1) ?: 0
        }
    }
    LaunchedEffect(centerIndex) {
        onSelectionChanged(options[centerIndex])
    }

    Box(modifier = Modifier.fillMaxWidth().height(itemHeight * 3)) {
        // 中间高亮条：标识"当前选中行"的位置
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .align(Alignment.Center)
                .background(Primary.copy(0.08f), RoundedCornerShape(8.dp))
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight),
            modifier = Modifier.fillMaxSize()
        ) {
            items(options) { value ->
                val isCenter = options[centerIndex] == value
                Box(
                    modifier = Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(value),
                        fontSize = if (isCenter) 20.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) Primary else TextSecondary.copy(0.5f)
                    )
                }
            }
        }
    }
}

/** 可点行：整行响应点击；右尾 [trailing] 默认是 ChevronRight 视觉指示，需要展示状态值（如自动锁定的"5 分钟"）时由调用方覆盖。 */
@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    /** 留空则不画副标题行（危险操作两项就只要标题）。 */
    subtitle: String = "",
    iconTint: Color = Primary,
    trailing: @Composable () -> Unit = {
        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
    },
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        trailing()
    }
}
