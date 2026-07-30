package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.OperationLogRepository
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.model.OperationType
import com.example.midun.data.real.CardDeviceException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val cardManager: SecurityCardManager,
    private val operationLog: OperationLogRepository
) : ViewModel() {

    sealed class InitState {
        object Idle : InitState()
        object Loading : InitState()
        data class Success(val bound: Boolean) : InitState()
        data class Error(val message: String) : InitState()
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object Success : LoginState()

        /**
         * [attemptsLeft] 三种取值，`LoginScreen` 据此渲染：
         * - `> 0`：真的密码错，文案后面追加「（剩余N次）」，登录按钮仍可用；
         * - `0`：尝试次数用尽，按钮禁用；
         * - [NO_ATTEMPT_SPENT]：与密码无关的失败（卡打不开/卡被拔走/绑定不符/密钥库损坏），
         *   只报原因、不显示次数、按钮保持可用，也**不消耗**尝试次数。
         */
        data class Error(val message: String, val attemptsLeft: Int) : LoginState()
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Idle)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private var loginAttempts = 0

    fun initDevice(password: String, confirmPassword: String, bindDevice: Boolean) {
        if (password != confirmPassword) {
            _initState.value = InitState.Error("两次密码不一致")
            return
        }
        if (password.length < 6) {
            _initState.value = InitState.Error("密码至少6位")
            return
        }
        viewModelScope.launch {
            _initState.value = InitState.Loading
            cardManager.initDevice(password, bindDevice)
                .onSuccess { _initState.value = InitState.Success(bindDevice) }
                .onFailure { _initState.value = InitState.Error(it.message ?: "初始化失败") }
        }
    }

    fun login(password: String) {
        if (loginAttempts >= MAX_ATTEMPTS) {
            _loginState.value = LoginState.Error(LOCKOUT_MESSAGE, 0)
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            cardManager.authenticate(password)
                .onSuccess {
                    loginAttempts = 0
                    operationLog.record(OperationType.LOGIN, "密码验证通过，设备ID匹配")
                    _loginState.value = LoginState.Success
                }
                .onFailure {
                    // 与密码无关的失败不计尝试次数（`[usb]` 2026-07-30）：盘压根没打开、卡被拔走、卡绑定了
                    // 别的设备、密钥库损坏——再输一次也是同一个结果，把它们计进去会把用户锁进「请联系技术
                    // 人员」，而他一个字都没输错。自 connectUsb 的探测只对「句柄铁定不可用」中止连接起，
                    // 内核未就绪这类码都会走到登录这里来报，这个区分从「稳妥」变成了必需。
                    if (it is CardDeviceException) {
                        _loginState.value = LoginState.Error(it.message ?: "安全卡打开失败", NO_ATTEMPT_SPENT)
                        return@onFailure
                    }
                    loginAttempts++
                    val attemptsLeft = MAX_ATTEMPTS - loginAttempts
                    _loginState.value = if (attemptsLeft <= 0) {
                        LoginState.Error(LOCKOUT_MESSAGE, 0)
                    } else {
                        LoginState.Error(it.message ?: "密码错误", attemptsLeft)
                    }
                }
        }
    }

    companion object {
        /** [LoginState.Error.attemptsLeft] 的哨兵值：本次失败与密码无关，没有消耗尝试次数。 */
        const val NO_ATTEMPT_SPENT = -1
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MESSAGE = "身份认证失败，请联系技术人员"
    }
}
