package com.example.midun.navigation

import android.util.Base64

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
        // 真卡模式 folderId = 隐藏区完整路径（如 `0:/工作文件`，含 `/` `:`）。若直接拼进路由，路径里的 `/`
        // 会被当成路由分隔符使 NavController 匹配不到目标而闪退（Mock 的 `folder_x` id 无此问题）。
        // 用 Base64(URL-safe, 无填充) 编成单个干净路径段（仅 [A-Za-z0-9_-]），由 [decodeFolderId] 显式还原，
        // 不依赖 Navigation 对 %2F 编码斜杠的处理。
        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        fun createRoute(folderId: String) =
            "file_detail/" + Base64.encodeToString(folderId.toByteArray(Charsets.UTF_8), B64_FLAGS)

        fun decodeFolderId(arg: String): String =
            runCatching { String(Base64.decode(arg, B64_FLAGS), Charsets.UTF_8) }.getOrDefault(arg)
    }
    object CreateFolder : Screen("create_folder")
}