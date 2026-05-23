package com.example.midun.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.midun.data.mock.MockUsbManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val mockUsbManager: MockUsbManager
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
            mockUsbManager.initDevice(password, bindDevice)
                .onSuccess { _initState.value = InitState.Success(bindDevice) }
                .onFailure { _initState.value = InitState.Error(it.message ?: "初始化失败") }
        }
    }

    fun login(password: String) {
        if (loginAttempts >= MAX_ATTEMPTS) {
            _loginState.value = LoginState.Error("尝试次数过多，请通过串口重置", 0)
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            mockUsbManager.authenticate(password)
                .onSuccess {
                    loginAttempts = 0
                    _loginState.value = LoginState.Success
                }
                .onFailure {
                    loginAttempts++
                    _loginState.value = LoginState.Error(
                        it.message ?: "密码错误",
                        MAX_ATTEMPTS - loginAttempts
                    )
                }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
