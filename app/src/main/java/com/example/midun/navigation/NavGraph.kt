package com.example.midun.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.midun.screen.*

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Splash.route) {
        composable(Screen.Splash.route) {
            SplashScreen(navController = navController)
        }

        composable(Screen.Init.route) {
            InitScreen(onInitComplete = {
                navController.navigate(Screen.Login.route) {
                    popUpTo(Screen.Init.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onForgotPassword = {
                    navController.navigate(Screen.Init.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onFolderClick = { folderId ->
                    navController.navigate(Screen.FileDetail.createRoute(folderId))
                },
                onCreateFolder = {
                    navController.navigate(Screen.CreateFolder.route)
                },
                onContactClick = { contactId ->
                    navController.navigate(Screen.ChatDetail.createRoute(contactId))
                },
                onQrCodeClick = {
                    navController.navigate(Screen.QrCode.route)
                },
                // 退出登录：用户选"导航 Splash 让其自然路由"——Splash 看到 status≠AUTHENTICATED
                // 会按既有路由表落到 Login（同时给一次 2.5s splash 动画作为视觉反馈）。
                onLogoutComplete = {
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // 恢复出厂：patch §M7 改动1 明确直跳 Init；wipeAll 已把 isInitialized=false。
                onFactoryResetComplete = {
                    navController.navigate(Screen.Init.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.ChatDetail.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            ChatDetailScreen(
                contactId = contactId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { navController.navigate(Screen.ContactProfile.createRoute(contactId)) },
                onGoConnect = { navController.navigate(Screen.QrCode.route) }
            )
        }

        composable(
            route = Screen.ContactProfile.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            ContactProfileScreen(
                contactId = contactId,
                onBack = { navController.popBackStack() },
                // 删除联系人后回列表：越过已失效的会话页，直接弹回 Main。
                onContactDeleted = { navController.popBackStack(Screen.Main.route, inclusive = false) }
            )
        }

        composable(Screen.QrCode.route) {
            QrCodeScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { contactId ->
                    // 连接建立（+备注）后进会话：**弹到 Main 为止**，保证栈里恒为 Main → 会话页一层。
                    //
                    // 原先只弹二维码页（popUpTo(QrCode)），弹不掉它下面的东西。而二维码页有两个入口：
                    // 会话列表的连接按钮（栈=Main→QR，弹完正好一层）、以及会话页顶栏的「前往建立连接」/
                    // 气泡里的「去建立连接」（栈=Main→Chat→QR，弹完剩 Main→Chat→Chat）。后者建联后栈里
                    // 压着两个**同一个联系人**的会话页，第一次返回弹掉上面那个、露出下面一模一样的一个，
                    // 用户看到的就是「返回没反应，要按两次」（2026-08-14 客户反馈；两端都从会话页进的
                    // 建连入口，故双方都中招）。
                    //
                    // launchSingleTop 再兜一层：万一同一次建联触发了两次回调，也不会叠出第二个会话页。
                    navController.navigate(Screen.ChatDetail.createRoute(contactId)) {
                        popUpTo(Screen.Main.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = Screen.FileDetail.route,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val folderId = Screen.FileDetail.decodeFolderId(backStackEntry.arguments?.getString("folderId") ?: "")
            FileDetailScreen(folderId = folderId, onBack = { navController.popBackStack() })
        }

        composable(Screen.CreateFolder.route) {
            CreateFolderScreen(onBack = { navController.popBackStack() })
        }
    }
}