package com.example.midun.util

/**
 * 手机号格式（NC7，无卡版本地账户标识）。仅做**国内 11 位手机号**格式校验——`1` 开头、第二位 3–9、共 11 位数字。
 * 无卡纯本地，无短信验证，这里只挡「填错/明显非手机号」，不核实号码真实归属。
 */
object PhoneFormat {
    private val CHINA_MOBILE = Regex("^1[3-9]\\d{9}$")

    fun isValidChinaMobile(phone: String): Boolean = CHINA_MOBILE.matches(phone)
}
