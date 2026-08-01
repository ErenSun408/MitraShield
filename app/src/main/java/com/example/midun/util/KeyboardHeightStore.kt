package com.example.midun.util

import android.content.Context

/**
 * 记住这台机器的软键盘高度（`[ui]` 2026-08-01）。
 *
 * 为什么要存：会话页的 ⊕ 面板与键盘共用输入栏下方那块空间，面板高度取「上次键盘高度」，两者等高才不会在
 * 切换时跳一下。但这个值原先只活在组合里、初值写死 280dp——**本次进程内从没弹过键盘就直接点 ⊕**，面板就按
 * 猜测值展开，等用户之后弹一次真键盘（华为的普遍高于 280dp），再切换时那一跳就露馅了。存下来，装机后第一次
 * 弹过键盘，此后每次冷启动都用真值。
 *
 * **为何用 SharedPreferences 而非项目惯用的 DataStore**（见 [com.example.midun.data.SettingsStore]）：这个值要在
 * **首帧组合时同步读到**才有意义，晚几毫秒到达就已经错过了用户点 ⊕ 的那一下；DataStore 只给异步 Flow。
 * 且它是纯 UI 外观参数、非用户设置也非隐私数据，不值得为它加一条 DI 链路。
 */
object KeyboardHeightStore {

    /** 从没测到过键盘时的猜测值。 */
    const val FALLBACK_DP = 280

    private const val PREFS = "midun_ui"
    private const val KEY = "keyboard_height_dp"

    /** 同步读；读不到或存储不可用时退回 [FALLBACK_DP]。 */
    fun read(context: Context): Int = runCatching {
        prefs(context).getInt(KEY, FALLBACK_DP)
    }.getOrDefault(FALLBACK_DP)

    /** 记下新测得的高度。与已存值相同则不写——键盘每次升起都会调一次，没必要反复落盘。 */
    fun write(context: Context, heightDp: Int) {
        runCatching {
            val store = prefs(context)
            if (store.getInt(KEY, -1) == heightDp) return
            store.edit().putInt(KEY, heightDp).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
