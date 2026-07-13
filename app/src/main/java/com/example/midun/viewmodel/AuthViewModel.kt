package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.OperationLogRepository
import com.example.midun.data.AccountManager
import com.example.midun.data.model.OperationType
import com.example.midun.util.PhoneFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accountManager: AccountManager,
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
        data class Error(val message: String, val attemptsLeft: Int) : LoginState()
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Idle)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private var loginAttempts = 0

    fun initDevice(phone: String, password: String, confirmPassword: String, bindDevice: Boolean) {
        if (!PhoneFormat.isValidChinaMobile(phone)) {
            _initState.value = InitState.Error("请输入正确的手机号")
            return
        }
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
            accountManager.initDevice(phone, password, bindDevice)
                .onSuccess { _initState.value = InitState.Success(bindDevice) }
                .onFailure { _initState.value = InitState.Error(it.message ?: "初始化失败") }
        }
    }

    fun login(phone: String, password: String) {
        if (loginAttempts >= MAX_ATTEMPTS) {
            _loginState.value = LoginState.Error(LOCKOUT_MESSAGE, 0)
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            accountManager.authenticate(phone, password)
                .onSuccess {
                    loginAttempts = 0
                    operationLog.record(OperationType.LOGIN, "密码验证通过，设备ID匹配")
                    _loginState.value = LoginState.Success
                }
                .onFailure {
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

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MESSAGE = "身份认证失败，请联系技术人员"
    }
}
