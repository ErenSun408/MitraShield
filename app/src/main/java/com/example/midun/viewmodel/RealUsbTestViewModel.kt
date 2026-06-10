package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.real.RealUsbManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 临时 DEV 测试 VM（M11.2 真机验证用）：从 DevControlPanel 触发 [RealUsbManager.openDevice]，
 * 把结果（成功 + SN / 失败原因）回灌给一个对话框,确认 FSShell SDK 在真机上能打开这张卡
 * （ABI / USB 权限 / OTG 只有真机暴露）。
 *
 * ⚠️ **临时件**：M11.3 把 Real 正式接入 app 后,本 VM 与 DevControlPanel 里的测试入口一并删除。
 */
@HiltViewModel
class RealUsbTestViewModel @Inject constructor(
    private val realUsbManager: RealUsbManager
) : ViewModel() {

    private val _result = MutableStateFlow<String?>(null)
    /** 非空 = 显示结果对话框；内容为成功/失败文案。 */
    val result: StateFlow<String?> = _result.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** 打开真卡 → 读 SN → 立即关闭,把整条链路结果汇成一段文本。 */
    fun testOpen() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            _result.value = "正在打开真卡…"
            val msg = realUsbManager.openDevice()
                .fold(
                    onSuccess = {
                        val sn = realUsbManager.getSerialNumber()
                        realUsbManager.closeDevice()
                        "✅ 打开成功\nSN: ${sn ?: "(SFDiskGetSN 读取失败)"}"
                    },
                    onFailure = { "❌ 打开失败\n${it.message}" }
                )
            _result.value = msg
            _testing.value = false
        }
    }

    fun clearResult() {
        if (!_testing.value) _result.value = null
    }
}
