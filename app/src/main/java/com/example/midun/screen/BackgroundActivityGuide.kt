package com.example.midun.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.midun.ui.theme.Danger
import com.example.midun.ui.theme.DangerBg
import com.example.midun.ui.theme.Primary
import com.example.midun.ui.theme.TextPrimary
import com.example.midun.ui.theme.TextSecondary

/**
 * 「允许后台活动」引导（`[network]` 2026-08-03，客户根因定位）。
 *
 * **为什么需要**：真机日志把断连钉死在系统侧——用户一开系统文件选取器或切去别的 App，本应用的两条 socket
 * 在同一毫秒被 `ECONNABORTED` 掐掉，而框架的 `onBlockedStatusChanged` **从未触发**。也就是说掐网的不是 AOSP
 * 那套（Doze / 待机分组 / 数据保护），而是厂商自家的后台管控——前台服务对它无效。客户实测确认：华为
 * 「应用管理 → 耗电详情 → 允许后台活动」打开后一切正常。
 *
 * **为什么只能跳到应用详情页**：华为把 systemmanager 的相关界面全用自家权限锁死了，实测（2026-08-03，
 * EMUI/P30）四条路只有最后一条能走——
 * - `DetailOfSoftConsumptionActivity`（耗电详情）→ 需 `huawei.android.permission.HW_SIGNATURE_OR_SYSTEM`（签名级）
 * - `StartupNormalAppListActivity`（启动管理）→ 需 `com.huawei.permission.external_app_settings.USE_COMPONENT`
 * - `HwPowerManagerActivity`（电池管理）→ 需 `com.huawei.systemmanager.permission.ACCESS_INTERFACE`
 * - `ACTION_APPLICATION_DETAILS_SETTINGS`（标准应用详情页）→ ✅
 *
 * 签名级权限只授予用厂商证书签名或位于系统分区的应用，我们两样都不可能有，故**这不是绕一下就能过的**。
 * 送到应用详情页之后剩下的两步只能靠文案讲清楚，见 [guideSteps]。
 *
 * **不做「检测开关是否已开」**：既然连它的界面都碰不到，就更没有 API 能查它的状态。故不检测，按客户定的
 * 口径来——首次插卡弹一次引导，之后在建联页常驻一条提示。
 */
object BackgroundActivityGuide {

    /**
     * 跳到本应用的系统设置详情页。用 [Activity] 作 context 启动，用户按返回即回到原界面；
     * 拿不到 Activity（理论上不会）才退回 `NEW_TASK`，那种情况下返回栈行为由系统决定。
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 落到应用详情页之后还要点的那几步。**按厂商给**：各家路径不同，给错了比不给更误导。
     * 认不出的机型给一句通用的——总比把华为的路径念给小米用户听强。
     */
    fun guideSteps(): List<String> = when (Build.MANUFACTURER.lowercase()) {
        "huawei", "honor" -> listOf("点击「耗电详情」", "打开「允许后台活动」")
        "xiaomi", "redmi" -> listOf("点击「省电策略」", "选择「无限制」")
        "oppo", "realme", "oneplus" -> listOf("点击「耗电管理」", "允许「后台运行」")
        "vivo" -> listOf("点击「电池」", "允许「后台高耗电」")
        else -> listOf("找到电池 / 耗电相关设置", "允许本应用在后台运行")
    }

    /**
     * 本进程是否已经自动弹过引导。**进程级、不落盘**——用户没勾「不再询问」时，每次重开 App 的首次插卡都该
     * 再提醒一次（这个设置一关就断连，提醒一次的代价远小于用户对着断连找不着北）；同一次运行里反复进出首页
     * 则不再打扰。用户点「去设置」主动打开的那次不走这里，见 [BackgroundActivityGuideDialog] 的 showSuppressOption。
     */
    @Volatile private var promptedThisProcess = false

    /** 本次该不该自动弹：只有本进程还没弹过才返回 true，并就地记下（同一进程内只会返回一次 true）。 */
    @Synchronized
    fun consumeAutoPrompt(): Boolean {
        if (promptedThisProcess) return false
        promptedThisProcess = true
        return true
    }

    /** 引导框里讲清「为什么要开」的那句。只讲用户能感知的后果，不讲 socket/前台服务那一层。 */
    const val WHY = "系统限制本应用在后台运行时，将导致您与他人的会话连接不稳定。"

    /**
     * 建联页常驻提示条的正文（尾部的「去设置」由 [HintBar] 的 actionText 接上）。
     * 措辞是「请确保」而不是「检测到…受限」——我们根本查不到那个开关的状态（见本类说明），不能把猜的说成测的。
     */
    const val HINT = "请确保系统允许应用后台活动，否则可能导致连接不稳定"
}

/**
 * 「允许后台活动」引导框。两个入口共用：
 * - **自动弹**（每进程首次插卡进主界面，见 [BackgroundActivityGuide.consumeAutoPrompt]）→ [showSuppressOption] = true，
 *   带「不再询问」勾选给不想被打扰的用户一个出口；
 * - **用户在建联页点「去设置」**→ [showSuppressOption] = false。那是用户自己要看的，再给个「不再询问」既没道理
 *   也容易误勾。
 *
 * [onDismiss] / [onOpenSettings] 都带上勾选态：勾了就是勾了，用哪个按钮关掉的不影响这个意思。
 */
@Composable
fun BackgroundActivityGuideDialog(
    onDismiss: (suppress: Boolean) -> Unit,
    onOpenSettings: (suppress: Boolean) -> Unit,
    showSuppressOption: Boolean = false
) {
    var suppress by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(suppress) },
        title = { Text("请允许应用后台活动") },
        text = {
            Column {
                Text(
                    BackgroundActivityGuide.WHY,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = TextPrimary
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    buildAnnotatedString {
                        append("点击")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("去设置") }
                        append("将为您跳转至本应用的系统设置页，请依次：")
                    },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.size(6.dp))
                BackgroundActivityGuide.guideSteps().forEachIndexed { index, step ->
                    Text("${index + 1}. $step", fontSize = 14.sp, lineHeight = 22.sp, color = TextPrimary)
                }
                if (showSuppressOption) {
                    Spacer(Modifier.size(8.dp))
                    // 去掉 Material 强制的 48dp 最小可点尺寸：那一圈留白把勾选行撑得离按钮老远。整行可点，
                    // 命中区由 Row 提供，不靠勾选框本身那点面积，故收掉不影响可用性。
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { suppress = !suppress }
                        ) {
                            Checkbox(checked = suppress, onCheckedChange = { suppress = it })
                            Spacer(Modifier.width(6.dp))
                            Text("不再询问", fontSize = 13.sp, color = TextSecondary)
                        }
                    }
                }
            }
        },
        // 与「重发消息」弹框同款：主操作是实心 Primary 按钮，次操作是灰字文本按钮。
        confirmButton = {
            Button(
                onClick = { onOpenSettings(suppress) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(suppress) }) { Text("暂不设置", color = TextSecondary) }
        }
    )
}

/**
 * 底部提示条（`[ui]`）：浅红底 + 红字，把「网络不对」「后台被限」这类**建联失败最常见的原因**从背景里拎出来
 * ——原来的灰字灰底和页面同色，用户根本注意不到（客户 2026-08-01）。
 *
 * [actionText] 非空时在文末接一段带下划线的可点文字（同会话顶栏「前往建立连接」的写法），点它才触发
 * [onAction]；整条不做点击，避免用户想叉掉却误触发跳转。
 *
 * **高度压到最低**（客户 2026-08-14 嫌占地方）：本页底部同时挂两条，原来每条约 48dp、两条近百 dp，
 * 把内容区挤得很紧。真正决定高度的是右侧那颗关闭按钮（36dp 比两行 12sp 文字还高），故按
 * 「关闭按钮 → 上下内边距 → 行高」的顺序依次收：28 + 4×2 = 36dp 封底，文字排到两行也就 38dp。
 * 字号 12sp 不动——再小就影响可读了，而这两条讲的恰恰是建联失败最常见的原因。
 */
@Composable
fun HintBar(
    text: String,
    onDismiss: () -> Unit,
    actionText: String? = null,
    onAction: () -> Unit = {}
) {
    Surface(color = DangerBg, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Danger, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            val body = buildAnnotatedString {
                append(text)
                if (actionText != null) {
                    append(" · ")
                    // 用 LinkAnnotation 而非已废弃的 ClickableText：命中区由文本布局自己算，
                    // 不必再按 offset 反查注解。
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = ACTION_TAG,
                            styles = TextLinkStyles(
                                SpanStyle(
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        ) { onAction() }
                    ) { append(actionText) }
                }
            }
            Text(
                body,
                color = Danger,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = Danger, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private const val ACTION_TAG = "action"
