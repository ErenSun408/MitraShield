package com.example.midun.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * USB安全卡连接状态
 * 演示用 - 模拟拔插效果
 */
object UsbState {
    var isConnected by mutableStateOf(true)
        private set

    fun connect() {
        isConnected = true
    }

    fun disconnect() {
        isConnected = false
    }

    fun toggle() {
        isConnected = !isConnected
    }
}