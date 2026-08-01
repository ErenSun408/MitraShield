package com.example.midun.diag

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
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
 *
 * **拦截要落成结论**（`[diag]` 2026-08-01）：光记一句「被拦了」还得再问客户一轮才知道该关哪个开关，故
 * 拦截发生时把系统里**所有能自查的后台限制开关**一并记下（见 [restrictionFacts]），并按优先级点名最可能的
 * 那一个（见 [verdict]）。同一组事实在进程启动时也记一行做基线：拦截当时的取值要和平时对比才有意义。
 *
 * 注意 `blocked=true` 的前提是**系统此刻把 App 判为后台**——前台可见的应用不会被拦。所以这行日志同时也在说
 * 「用户切走/锁屏了」，这正是 [ActivityManager.RunningAppProcessInfo.importance] 也一并记下的原因。
 */
object NetworkEventLog {

    @Volatile private var lastSummary: String? = null
    @Volatile private var appContext: Context? = null

    /** 进程启动时注册一次，随进程存活（不注销：进程没了系统自会回收）。缺权限/取不到服务时静默跳过。 */
    fun start(context: Context) {
        appContext = context.applicationContext
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerDefaultNetworkCallback(callback(cm)) }
            .onFailure { CardPerf.mark("[diag] 网络监听注册失败：${it.javaClass.simpleName}") }
        // 基线：现在没被拦，各开关是什么状态。拦截那一刻的取值只有和它对比才看得出是谁变了。
        CardPerf.mark("[diag] 后台限制基线：${restrictionFacts(Limits(cm, null))}")
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
            if (!blocked) {
                CardPerf.mark("[diag] 本应用网络恢复放行")
                return
            }
            val limits = Limits(cm, network) // 只读一次：结论与事实必须出自同一个快照
            CardPerf.mark("[diag] ⚠ 本应用的网络被系统拦截 —— ${verdict(limits)}")
            CardPerf.mark("[diag]   拦截时各开关：${restrictionFacts(limits)}")
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

    // ---- 后台限制自查（拦截发生时把「是哪一种」写死在日志里） ----

    /**
     * 系统里能自查到的后台限制开关快照。取不到的项为 null（旧系统或厂商裁剪了对应服务）。
     *
     * [network] 传 null 时按当前默认网络算「是否按流量计费」——数据保护只拦按量网络，这一项决定了它是不是
     * 本次拦截的嫌疑人。
     */
    private class Limits(cm: ConnectivityManager, network: Network?) {
        val ctx = appContext
        val pm = ctx?.getSystemService(PowerManager::class.java)
        val am = ctx?.getSystemService(ActivityManager::class.java)

        /** 数据保护（Data Saver）对本应用的态度。 */
        val dataSaver: Int? = runCatching { cm.restrictBackgroundStatus }.getOrNull()

        /** 当前网络是否按流量计费。**热点 Wi-Fi 默认就算按量**，这是最容易被忽略的一种命中。 */
        val metered: Boolean? = runCatching {
            val target = network ?: cm.activeNetwork
            cm.getNetworkCapabilities(target)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not()
        }.getOrNull()

        /** true = **未**豁免电池优化（Doze / 省电模式要靠它才拦得住本应用）。 */
        val batteryOptimized: Boolean? = runCatching {
            ctx?.let { pm?.isIgnoringBatteryOptimizations(it.packageName)?.not() }
        }.getOrNull()

        val powerSave: Boolean? = runCatching { pm?.isPowerSaveMode }.getOrNull()
        val doze: Boolean? = runCatching { pm?.isDeviceIdleMode }.getOrNull()

        /** 用户在「电池」里把本应用设成了「受限制」——最狠的一档，后台一律断网。API 28+。 */
        val bgRestricted: Boolean? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am?.isBackgroundRestricted else null
        }.getOrNull()

        /** 待机分组：落到 RARE/RESTRICTED 就会被限流甚至断网。API 28+。 */
        val bucket: Int? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ctx?.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            } else null
        }.getOrNull()

        /** 本进程此刻的重要度。前台应用不会被拦，所以这一项决定结论往哪边走。 */
        val importance: Int? = runCatching {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        }.getOrNull()

        val isForeground: Boolean
            get() = importance != null && importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    /** 各开关的取值，一行记完；拦截时与启动基线对比即可看出是谁变了。 */
    private fun restrictionFacts(l: Limits): String {
        val dataSaver = when (l.dataSaver) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "关"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "开(本应用已豁免)"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "开(本应用未豁免)"
            else -> "?"
        }
        return "数据保护=$dataSaver" +
            "，当前网络=${l.metered.yesNo("按流量计费", "不计费")}" +
            "，电池优化=${l.batteryOptimized.yesNo("未豁免", "已豁免")}" +
            "，省电模式=${l.powerSave.onOff()}" +
            "，Doze=${l.doze.onOff()}" +
            "，后台受限档=${l.bgRestricted.yesNo("是", "否")}" +
            "，待机分组=${bucketName(l.bucket)}" +
            "，本进程=${importanceName(l.importance)}"
    }

    /**
     * 点名最可能的那一个开关，按「越具体越优先」排序：一次拦截可能同时满足好几条，但排在前面的那条一旦成立，
     * 后面的就都是陪跑。全不命中时结论同样有价值——那基本就是厂商自家的后台管控，系统 API 查不到。
     */
    private fun verdict(l: Limits): String {
        val oem = "系统标准开关全未命中：极可能是厂商自家的后台管控（华为「应用启动管理」/ 小米「省电策略」等），" +
            "系统 API 查不到，需在手机管家里放行"
        val cause = when {
            l.bgRestricted == true ->
                "本应用被设为「受限制」，后台一律断网 → 设置-应用-MiDun-电池，改「不受限制」"
            l.dataSaver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && l.metered != false ->
                "数据保护开着、本应用未列白名单，且当前网络按流量计费（两台手机走热点时默认就是按量）"
            l.bucket == BUCKET_RESTRICTED ->
                "本应用被系统压到「受限」待机分组（长期不用会掉到这一档）"
            l.powerSave == true && l.batteryOptimized != false ->
                "省电模式开着且本应用未豁免电池优化"
            l.doze == true && l.batteryOptimized != false ->
                "系统已进 Doze 且本应用未豁免电池优化"
            l.batteryOptimized == true ->
                "未命中具体开关，但本应用未豁免电池优化，后台待机被掐的可能性最大"
            else -> oem
        }
        // 前台应用本不该被拦：真出现了，上面按后台规则推的结论就不可信，直接指向厂商定制。
        return if (l.isForeground) "$cause（注意：本进程此刻是前台，被拦不合系统标准行为，优先怀疑厂商定制）"
        else cause
    }

    private fun Boolean?.yesNo(yes: String, no: String) = if (this == null) "?" else if (this) yes else no
    private fun Boolean?.onOff() = yesNo("开", "关")

    private fun bucketName(bucket: Int?) = when (bucket) {
        null -> "?"
        BUCKET_ACTIVE -> "ACTIVE"
        BUCKET_WORKING_SET -> "WORKING_SET"
        BUCKET_FREQUENT -> "FREQUENT"
        BUCKET_RARE -> "RARE(已限流)"
        BUCKET_RESTRICTED -> "RESTRICTED(最严)"
        else -> bucket.toString()
    }

    private fun importanceName(importance: Int?) = when (importance) {
        null -> "?"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "前台服务"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "服务"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "已缓存(后台)"
        else -> "后台($importance)"
    }

    // UsageStatsManager 的分组常量：RESTRICTED 是 API 30 才加的，硬编码避免为一个数字加版本判断。
    private const val BUCKET_ACTIVE = 10
    private const val BUCKET_WORKING_SET = 20
    private const val BUCKET_FREQUENT = 30
    private const val BUCKET_RARE = 40
    private const val BUCKET_RESTRICTED = 45
}
