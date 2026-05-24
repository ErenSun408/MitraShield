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
                }
            )
        }

        composable(
            route = Screen.ChatDetail.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            ChatDetailScreen(contactId = contactId, onBack = { navController.popBackStack() })
        }

        composable(Screen.QrCode.route) {
            QrCodeScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.FileDetail.route,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            FileDetailScreen(folderId = folderId, onBack = { navController.popBackStack() })
        }

        composable(Screen.CreateFolder.route) {
            CreateFolderScreen(onBack = { navController.popBackStack() })
        }
    }
}