package com.example.midun.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.example.midun.data.real.CardPerf

/**
 * 默认网络的变化写进诊断日志（`[diag]` 2026-07-31）。
 *
 * 为什么需要：2026-07-31 两台真机的日志显示，六次断连里 App 自己一条断连逻辑都没触发 —— 一端恒为
 * `Connection reset`（收到对端 RST），另一端恒为 `Software caused connection abort`（本机协议栈中止），
 * 且这个分工**跟着设备走、不跟着 A/B 角色走**。最像的解释是那一端的 Wi-Fi 在反复瞬断/切换，把两条 socket
 * 一起掐掉。但那还是推断——本类把它变成证据：断连时刻旁边若紧挨着一条网络丢失/切换/IP 变更，即可定案。
 *
 * 关注四件事，都能独立掐死一条 TCP：
 * - [onLost] / [onAvailable]：默认网络没了/换了；
 * - 传输类型变化（WIFI ↔ 蜂窝）：华为「WLAN+/智能选网」在 Wi-Fi 弱时自动切走，切走即全断；
 * - **本机 IP 变化**：换 AP、DHCP 续租拿到新地址，旧连接当场失效；
 * - [onBlockedStatusChanged]：系统把本应用的网络**拦了**（省电策略 / 数据保护），API 29+。
 *
 * 去重：容量/链路属性回调触发很频繁（信号强度抖动都会来一发），故只在**摘要串变化时**才记一行，避免刷屏
 * 淹掉 `[net]` 的断连线索。
 */
object NetworkEventLog {

    @Volatile private var lastSummary: String? = null

    /** 进程启动时注册一次，随进程存活（不注销：进程没了系统自会回收）。缺权限/取不到服务时静默跳过。 */
    fun start(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerDefaultNetworkCallback(callback(cm)) }
            .onFailure { CardPerf.mark("[diag] 网络监听注册失败：${it.javaClass.simpleName}") }
    }

    private fun callback(cm: ConnectivityManager) = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            lastSummary = null // 换网了，强制记一行新的
            log(cm, network, "网络可用")
        }

        override fun onLost(network: Network) {
            lastSummary = null
            CardPerf.mark("[diag] ⚠ 默认网络丢失 —— 此刻的连接会被系统当场中止")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            log(cm, network, "网络变化")
        }

        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            log(cm, network, "链路变化")
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            lastSummary = null
            CardPerf.mark(
                if (blocked) "[diag] ⚠ 本应用的网络被系统拦截（省电策略/数据保护）" else "[diag] 本应用网络恢复放行"
            )
        }
    }

    /** 摘要 = 传输类型 + 是否已验证 + 接口名 + 本机 IP；与上次相同则不记。 */
    private fun log(cm: ConnectivityManager, network: Network, event: String) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val props = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val transport = when {
            caps == null -> "未知"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val addresses = props?.linkAddresses.orEmpty()
            .map { it.address.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        val summary = "$transport${if (validated) "(已联网)" else "(未验证)"}" +
            "，接口=${props?.interfaceName ?: "?"}，IP=${addresses.ifEmpty { "无" }}"
        if (summary == lastSummary) return // 只记真正的变化，别被信号抖动刷屏
        lastSummary = summary
        CardPerf.mark("[diag] $event：$summary")
    }
}
