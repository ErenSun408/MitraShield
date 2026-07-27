package com.example.midun.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "midun_settings")

/**
 * 应用设置持久化（M11.6.4）。当前承载「自动锁定超时」——经 Jetpack DataStore 存于**手机本地**。
 *
 * **偏离 M11 计划「写卡」的说明**：自动锁定时长是非敏感的 App 偏好，且需在认证**之前/之时**就生效；若存卡需
 * 盘已打开才能读，存在「读设置 → 需认证 → 需设置」的时序倒挂。故落手机本地（项目早已预置 datastore-preferences
 * 依赖正为此）。卡内只放隐私数据（文件/聊天/日志），不放这类 UI 偏好。
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.settingsDataStore

    val inactivityTimeoutMinutes: Flow<Int> =
        store.data.map { it[KEY_INACTIVITY_TIMEOUT_MIN] ?: DEFAULT_TIMEOUT_MIN }

    suspend fun setInactivityTimeout(minutes: Int) {
        store.edit { it[KEY_INACTIVITY_TIMEOUT_MIN] = minutes }
    }

    /**
     * 息屏自动退出（彻底杀进程）延迟，单位秒。取值约定：
     * - [SCREEN_OFF_EXIT_OFF]（-1）= 关闭（默认，不因息屏退出）
     * - [SCREEN_OFF_EXIT_IMMEDIATE]（0）= 息屏即退出
     * - 正数 = 息屏后延迟 N 秒退出（期间亮屏/回前台则取消）
     * 与「自动锁定」正交：前者杀进程，后者仅登出。
     */
    val screenOffExitSeconds: Flow<Int> =
        store.data.map { it[KEY_SCREEN_OFF_EXIT_SEC] ?: SCREEN_OFF_EXIT_OFF }

    suspend fun setScreenOffExitSeconds(seconds: Int) {
        store.edit { it[KEY_SCREEN_OFF_EXIT_SEC] = seconds }
    }

    /**
     * 「最近操作」总开关（客户 2026-07-28 要求：默认关闭）。关闭时首页不显示该区块，后台也**不记录**；
     * 开启后才从那一刻起记录（见 `OperationLogRepository`——关闭时 `record` 直接丢弃，且清掉卡上残留）。
     * 与自动锁定同理落手机本地：这是 UI 偏好而非隐私数据，且需在开盘前就能读到。
     */
    val operationLogEnabled: Flow<Boolean> =
        store.data.map { it[KEY_OPERATION_LOG_ENABLED] ?: OPERATION_LOG_DEFAULT }

    suspend fun setOperationLogEnabled(enabled: Boolean) {
        store.edit { it[KEY_OPERATION_LOG_ENABLED] = enabled }
    }

    /**
     * USB 驱动模式（鸿蒙 2.0 登录慢的诊断/测试开关，登录页隐藏面板可切、重启保留）：
     * [DRIVER_MODE_LIBUSB]（0，默认）= libusb 通道；[DRIVER_MODE_NATIVE]（2）= android 原生（DEVFS）通道。
     */
    val driverMode: Flow<Int> =
        store.data.map { it[KEY_DRIVER_MODE] ?: DRIVER_MODE_LIBUSB }

    /** 一次性读当前驱动模式（connectUsb 在开盘前取用）。 */
    suspend fun getDriverMode(): Int = driverMode.first()

    suspend fun setDriverMode(mode: Int) {
        store.edit { it[KEY_DRIVER_MODE] = mode }
    }

    companion object {
        const val OPERATION_LOG_DEFAULT = false
        private val KEY_OPERATION_LOG_ENABLED = booleanPreferencesKey("operation_log_enabled")

        const val DEFAULT_TIMEOUT_MIN = 5
        private val KEY_INACTIVITY_TIMEOUT_MIN = intPreferencesKey("inactivity_timeout_min")

        const val SCREEN_OFF_EXIT_OFF = -1
        const val SCREEN_OFF_EXIT_IMMEDIATE = 0
        private val KEY_SCREEN_OFF_EXIT_SEC = intPreferencesKey("screen_off_exit_sec")

        const val DRIVER_MODE_LIBUSB = 0
        const val DRIVER_MODE_NATIVE = 2
        private val KEY_DRIVER_MODE = intPreferencesKey("usb_driver_mode")
    }
}
