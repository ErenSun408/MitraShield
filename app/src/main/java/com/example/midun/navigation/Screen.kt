package com.example.midun.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Init : Screen("init")
    object Login : Screen("login")
    object Main : Screen("main")
    object ChatDetail : Screen("chat_detail/{contactId}") {
        fun createRoute(contactId: String) = "chat_detail/$contactId"
    }
    object ContactProfile : Screen("contact_profile/{contactId}") {
        fun createRoute(contactId: String) = "contact_profile/$contactId"
    }
    object QrCode : Screen("qr_code")
    object FileDetail : Screen("file_detail/{folderId}") {
        fun createRoute(folderId: String) = "file_detail/$folderId"
    }
    object CreateFolder : Screen("create_folder")
}