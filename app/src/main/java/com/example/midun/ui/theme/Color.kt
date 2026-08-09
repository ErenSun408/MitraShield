package com.example.midun.ui.theme

import androidx.compose.ui.graphics.Color

// 密盾主题色 - 安全感的深蓝+科技绿
val Primary = Color(0xFF1A3A5C)
val PrimaryLight = Color(0xFF2C5F8A)
val PrimaryDark = Color(0xFF0D1F33)
val Accent = Color(0xFF00C9A7)
val AccentLight = Color(0xFF4DDBBD)
val Surface = Color(0xFFF5F7FA)
val CardBg = Color(0xFFFFFFFF)
/**
 * 只读文本框/图标按钮的灰底（如邀请码页的邀请链接框）。比 [Surface] 深一档——页面背景就是 [Surface]，
 * 拿它当填充会跟底色糊成一片。
 */
val FieldBg = Color(0xFFE8ECF1)
val TextPrimary = Color(0xFF1A1A2E)
val TextSecondary = Color(0xFF6B7280)
val Danger = Color(0xFFEF4444)
/** [Danger] 的浅色底：承载红字提示条（如邀请码页的网络提示），比灰底显眼但不刺眼。 */
val DangerBg = Color(0xFFFDECEC)
val Warning = Color(0xFFF59E0B)
val Success = Color(0xFF10B981)
/** 会话顶栏「阅后即焚」未开启时的火苗色：深蓝顶栏上仍读得出是灰的（开启为 [Warning] 橙）。 */
val BurnIconOff = Color(0xFF9CA3AF)
val ChatBubbleMine = Color(0xFF1A3A5C)
val ChatBubbleOther = Color(0xFFE8ECF1)
