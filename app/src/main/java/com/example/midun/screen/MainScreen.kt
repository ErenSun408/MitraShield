package com.example.midun.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.ChatViewModel

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    // route 暂为占位：当前 Tab 切换用 forEachIndexed 的 index（见 MainScreen 的 when），
    // 未读取此字段；保留以备将来若改回嵌套 NavHost 的路由式切换时复用。
    val route: String
)

/**
 * 登录后的主框架（v4 §4「主页与底部导航」），经 `Screen.Main` 进入。承载底部 4 个 Tab：
 * 首页 / 文件夹 / 通信 / 设置。
 *
 * 与 v4 差异（详见 docs/design-deviations.md「M4」）：用 `selectedTab + when` 切换 Tab，
 * 而非 v4 的嵌套 NavHost；故每个 Tab 是叶子内容，详情页（FileDetail/ChatDetail/QrCode）
 * 仍走顶层 NavGraph。切走的 Tab 其 `rememberSaveable` 状态由 `rememberSaveableStateHolder` 保留。
 *
 * M4 验收核实（2026-05）：① 登录后可在 4 Tab 间切换 ✓；② 拔卡锁定由全局
 * UsbDisconnectedOverlay 兜底（见 M2 偏离）✓；③ 底部高亮 `selected = selectedTab == index` ✓。
 */
@Composable
fun MainScreen(
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onContactClick: (String) -> Unit,
    onQrCodeClick: () -> Unit,
    onLogoutComplete: () -> Unit,
    onFactoryResetComplete: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    // 底部"通信"Tab 的真实未读角标。chatViewModel 与内部 ChatListScreen 同属 Main
    // NavBackStackEntry → 同一实例；进/返本屏重读单例，使 ChatDetail 清未读后角标同步更新。
    val contacts by chatViewModel.contacts.collectAsState()
    val totalUnread = contacts.sumOf { it.unreadCount }
    LaunchedEffect(Unit) { chatViewModel.loadContacts() }

    val navItems = listOf(
        BottomNavItem("首页", Icons.Default.Home, "home"),
        BottomNavItem("私藏", Icons.Default.Folder, "files"),
        BottomNavItem("波一下", Icons.Default.Chat, "chat"),
        BottomNavItem("设置", Icons.Default.Settings, "settings"),
    )
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder = rememberSaveableStateHolder()
    // 文件夹 Tab 的结果 Snackbar 由本壳托管，使其落在底部导航栏之上（而非被 FilesScreen 内 FAB 顶起）。
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            // 通信 Tab 显示真实总未读数（M6 接 ChatViewModel 后替换 M0 写死的角标）。
                            if (index == 2 && totalUnread > 0) {
                                BadgedBox(badge = { Badge { Text("$totalUnread") } }) {
                                    Icon(item.icon, item.label)
                                }
                            } else {
                                Icon(item.icon, item.label)
                            }
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Primary,
                            selectedTextColor = Primary,
                            indicatorColor = Accent.copy(0.15f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // 用 SaveableStateProvider 按 Tab 分桶保存状态：切走的 Tab 退出 composition 后，
            // 其 rememberSaveable 状态（含 rememberScrollState 的滚动位置）会被保留，切回时恢复。
            // 补偿 selectedTab+when 方案相对嵌套 NavHost 缺失的 saveState/restoreState 行为。
            saveableStateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        onNavigateToFiles = { selectedTab = 1 },
                        onNavigateToChat = { selectedTab = 2 },
                        onNavigateToSettings = { selectedTab = 3 }
                    )
                    1 -> FilesScreen(
                        onFolderClick = onFolderClick,
                        onCreateFolder = onCreateFolder,
                        snackbarHostState = snackbarHostState
                    )
                    2 -> ChatListScreen(onContactClick = onContactClick, onQrCodeClick = onQrCodeClick)
                    3 -> SettingsScreen(
                        onLogoutComplete = onLogoutComplete,
                        onFactoryResetComplete = onFactoryResetComplete
                    )
                }
            }
        }
    }
}