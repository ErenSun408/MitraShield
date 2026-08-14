package com.example.midun.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.midun.data.model.FileSort
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
     * 用户是否勾了「允许后台活动」引导框里的**不再询问**（`[network]` 2026-08-03）。
     *
     * 只有勾了才永久不弹；没勾的话**每个进程的首次插卡都会再弹一次**（那一半的判据是进程内存标志，见
     * [com.example.midun.screen.BackgroundActivityGuide]）——这个设置一关就断连，值得每次开 App 提醒一次，
     * 而勾选给了不想被打扰的用户一个明确出口。建联页上那条常驻提示条不受此影响，一直都在。
     *
     * 落手机本地而非卡上：与自动锁定同理，这是 UI 偏好、且要在开盘前后都读得到；更重要的是它描述的是
     * **这台手机的系统设置**，跟着卡走反而是错的（同一张卡插到另一台手机上，那台照样需要引导）。
     */
    val backgroundGuideSuppressed: Flow<Boolean> =
        store.data.map { it[KEY_BACKGROUND_GUIDE_SUPPRESSED] ?: false }

    suspend fun setBackgroundGuideSuppressed(suppressed: Boolean) {
        store.edit { it[KEY_BACKGROUND_GUIDE_SUPPRESSED] = suppressed }
    }

    /**
     * 列表排序方式（客户 2026-08-14）。**文件夹与文件各存一份**，不共用。
     *
     * 分开的理由：排序的收益几乎全在文件列表（多、常新增，默认要「最新在前」），而文件夹是导航结构
     * （少、稳定，靠位置记忆）。共用一份的话，用户为了找某个文件把排序改成名称，退出来连文件夹列表都跟着
     * 重排一遍——代价落在了收益之外的那个列表上。两份的默认值也本就不同，见 [FileSort] 的两组常量。
     *
     * **落 DataStore 而不是 ViewModel**：私藏清隅与文件夹详情是两个 NavBackStackEntry，各自拿到不同的
     * `FileViewModel` 实例，靠 VM 存则每次进出都归零。同自动锁定那几项：属 UI 偏好、不进卡。
     */
    val folderSort: Flow<FileSort> = store.data.map {
        FileSort(
            field = FileSort.fieldOf(it[KEY_FOLDER_SORT_FIELD], FileSort.FOLDER_DEFAULT.field),
            descending = it[KEY_FOLDER_SORT_DESC] ?: FileSort.FOLDER_DEFAULT.descending
        )
    }

    suspend fun setFolderSort(sort: FileSort) {
        store.edit {
            it[KEY_FOLDER_SORT_FIELD] = sort.field.name
            it[KEY_FOLDER_SORT_DESC] = sort.descending
        }
    }

    /** 文件列表排序，与 [folderSort] 各自独立。 */
    val fileSort: Flow<FileSort> = store.data.map {
        FileSort(
            field = FileSort.fieldOf(it[KEY_FILE_SORT_FIELD], FileSort.FILE_DEFAULT.field),
            descending = it[KEY_FILE_SORT_DESC] ?: FileSort.FILE_DEFAULT.descending
        )
    }

    suspend fun setFileSort(sort: FileSort) {
        store.edit {
            it[KEY_FILE_SORT_FIELD] = sort.field.name
            it[KEY_FILE_SORT_DESC] = sort.descending
        }
    }

    companion object {
        private val KEY_BACKGROUND_GUIDE_SUPPRESSED = booleanPreferencesKey("background_guide_suppressed")

        const val OPERATION_LOG_DEFAULT = false
        private val KEY_OPERATION_LOG_ENABLED = booleanPreferencesKey("operation_log_enabled")

        const val DEFAULT_TIMEOUT_MIN = 5
        private val KEY_INACTIVITY_TIMEOUT_MIN = intPreferencesKey("inactivity_timeout_min")

        const val SCREEN_OFF_EXIT_OFF = -1
        const val SCREEN_OFF_EXIT_IMMEDIATE = 0
        private val KEY_SCREEN_OFF_EXIT_SEC = intPreferencesKey("screen_off_exit_sec")

        /**
         * 排序偏好：**依据与方向各存各的**（同 UI 上那两栏，见 [FileSort]），文件夹与文件再各一套，共四把键。
         * 依据存枚举名而非序号，理由见 [FileSort.fieldOf]。
         */
        private val KEY_FOLDER_SORT_FIELD = stringPreferencesKey("folder_sort_field")
        private val KEY_FOLDER_SORT_DESC = booleanPreferencesKey("folder_sort_desc")
        private val KEY_FILE_SORT_FIELD = stringPreferencesKey("file_sort_field")
        private val KEY_FILE_SORT_DESC = booleanPreferencesKey("file_sort_desc")
    }
}
