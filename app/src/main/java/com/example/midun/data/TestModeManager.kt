package com.example.midun.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无卡测试模式标志（进程内、不持久化、重启即失效）。
 *
 * 给手中暂无安全卡的客户测通信/文件/语音用：默认真卡（[isTestMode]=false），用户在常态三点面板点
 * 「无卡测试」后置位。置位后：① MainActivity 即使 DISCONNECTED 也渲染 NavGraph、屏蔽拔卡遮罩；
 * ② Splash 直跳 Main（绕过 Init/Login，认证要卡）；③ T2 起文件 staging IO 切手机本地实现。
 *
 * 故意不持久化：避免污染正式真卡客户——重启回到默认真卡态。真卡数据/加密路径不受本标志影响。
 */
@Singleton
class TestModeManager @Inject constructor() {

    private val _isTestMode = MutableStateFlow(false)
    val isTestMode: StateFlow<Boolean> = _isTestMode.asStateFlow()

    /** 进入无卡测试模式（不可逆，退出靠重启 App）。 */
    fun enable() { _isTestMode.value = true }
}
