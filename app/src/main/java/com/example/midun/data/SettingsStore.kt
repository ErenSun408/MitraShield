package com.example.midun.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
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

    companion object {
        const val DEFAULT_TIMEOUT_MIN = 5
        private val KEY_INACTIVITY_TIMEOUT_MIN = intPreferencesKey("inactivity_timeout_min")
    }
}
