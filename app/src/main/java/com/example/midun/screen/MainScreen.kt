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
import com.example.midun.ui.theme.*

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: String
)

@Composable
fun MainScreen(
    onFolderClick: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onContactClick: (String) -> Unit,
    onQrCodeClick: () -> Unit
) {
    val navItems = listOf(
        BottomNavItem("首页", Icons.Default.Home, "home"),
        BottomNavItem("文件夹", Icons.Default.Folder, "files"),
        BottomNavItem("通信", Icons.Default.Chat, "chat"),
        BottomNavItem("设置", Icons.Default.Settings, "settings"),
    )
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            if (index == 2 && true) { // 模拟有未读消息
                                BadgedBox(badge = { Badge { Text("3") } }) {
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
                        onNavigateToSettings = { selectedTab = 3 },
                        onQrCodeClick = onQrCodeClick
                    )
                    1 -> FilesScreen(onFolderClick = onFolderClick, onCreateFolder = onCreateFolder)
                    2 -> ChatListScreen(onContactClick = onContactClick, onQrCodeClick = onQrCodeClick)
                    3 -> SettingsScreen()
                }
            }
        }
    }
}