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
                onScanConnected = { navController.popBackStack() }
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