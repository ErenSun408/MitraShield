package com.example.midun.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 本机当前接入网络的判定，供邀请码页的提示条使用（客户需求 2026-07-31）。
 *
 * 背景：当前版本的跨网直连在现场只在**电信数据 ↔ 电信数据**之间验证通过（2026-07-31 真机日志）。家宽 Wi-Fi
 * 之间要通，需要两端都有公网 IPv6 且出码端路由器放行入站，普遍不成立。所以在建联页给一句「你现在是什么网、
 * 对端需要是什么网」的**参考**提示，帮用户少走弯路——**不改任何连接逻辑**，纯提示。
 *
 * 判定不花任何权限：`ConnectivityManager` 拿接入类型，`TelephonyManager.networkOperator`（MCC+MNC）拿运营商，
 * 后者取不到时退回 `networkOperatorName` 里的中文关键字。
 */
object NetworkStatus {

    /** 本机接入类型。 */
    enum class Kind { TELECOM, OTHER_CARRIER, WIFI, OTHER, NONE }

    /** [kind] 为 [Kind.OTHER_CARRIER] 时，[carrier] 是「移动」「联通」这类可直接嵌进文案的短名。 */
    data class Info(val kind: Kind, val carrier: String?)

    fun current(context: Context): Info {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Info(Kind.NONE, null)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return Info(Kind.NONE, null)
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Info(Kind.NONE, null)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Info(Kind.WIFI, null)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularInfo(context)
            else -> Info(Kind.OTHER, null)
        }
    }

    /** 提示条文案。末尾统一带免责，因为能不能通最终只有实测算数。 */
    fun hint(info: Info): String {
        val head = when (info.kind) {
            Kind.TELECOM -> "本端当前为电信网络，对端也需为电信网络"
            Kind.OTHER_CARRIER -> "本端当前为${info.carrier ?: "其他"}网络，需切换至电信网络"
            Kind.WIFI -> "本端当前为wifi网络，需切换至电信网络"
            Kind.OTHER -> "本端当前为其他网络，需切换至电信网络"
            Kind.NONE -> "本端当前无网络，需切换至电信网络"
        }
        return "$head（仅供参考，请实际测试为准）"
    }

    /** MCC+MNC 判运营商；取不到号段时退回运营商名里的关键字。 */
    private fun cellularInfo(context: Context): Info {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val operator = runCatching { tm?.networkOperator }.getOrNull()
            ?.takeIf { it.length >= 5 }
            ?: runCatching { tm?.simOperator }.getOrNull()?.takeIf { it.length >= 5 }
        val name = runCatching { tm?.networkOperatorName }.getOrNull().orEmpty()
        val mnc = operator?.substring(3)
        return when {
            mnc in TELECOM_MNC || name.contains("电信") -> Info(Kind.TELECOM, "电信")
            mnc in MOBILE_MNC || name.contains("移动") -> Info(Kind.OTHER_CARRIER, "移动")
            mnc in UNICOM_MNC || name.contains("联通") -> Info(Kind.OTHER_CARRIER, "联通")
            mnc in BROADNET_MNC || name.contains("广电") -> Info(Kind.OTHER_CARRIER, "广电")
            else -> Info(Kind.OTHER_CARRIER, name.ifBlank { "其他" })
        }
    }

    // 中国大陆 MCC=460；下列为各家的 MNC（号段有增补，故按集合判、认不出的落到「其他」）。
    private val TELECOM_MNC = setOf("03", "05", "11")
    private val MOBILE_MNC = setOf("00", "02", "04", "07", "08")
    private val UNICOM_MNC = setOf("01", "06", "09")
    private val BROADNET_MNC = setOf("15")
}

/**
 * 提示文案的 Compose 入口：随网络变化实时更新（用户在本页切了 Wi-Fi/数据，提示要跟着变，否则会误导）。
 * 回调在 binder 线程回来，写 Compose 状态是安全的（快照状态自带同步，重组仍排到主线程）。
 */
@Composable
fun rememberNetworkHint(): String {
    val context = LocalContext.current
    var info by remember { mutableStateOf(NetworkStatus.current(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() { info = NetworkStatus.current(context) }
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return NetworkStatus.hint(info)
}
