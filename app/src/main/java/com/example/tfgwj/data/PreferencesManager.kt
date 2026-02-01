package com.example.tfgwj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tfgwj_preferences")

class PreferencesManager(private val context: Context) {
    
    private object PreferencesKeys {
        val LAST_SELECTED_FOLDER_PATH = stringPreferencesKey("last_selected_folder_path")
        val LAST_MAIN_PACK_PATH = stringPreferencesKey("last_main_pack_path")  // 上次选择的主包路径
        val SHIZUKU_AUTHORIZED = booleanPreferencesKey("shizuku_authorized")
        val AUTO_STOP_APP = booleanPreferencesKey("auto_stop_app")
        val APP_PACKAGE_NAME = stringPreferencesKey("app_package_name")
        val USE_AUTO_PASSWORD = booleanPreferencesKey("use_auto_password")
        val LAST_DECRYPT_PASSWORD = stringPreferencesKey("last_decrypt_password")
        val LOCKED_TIME = longPreferencesKey("locked_time")  // 锁定的时间
        val LOCKED_TIME_ENABLED = booleanPreferencesKey("locked_time_enabled")  // 是否启用锁定时间
    }
    
    // 上次选择的文件夹路径
    val lastSelectedFolderPath: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_SELECTED_FOLDER_PATH]
        }
    
    suspend fun saveLastSelectedFolderPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SELECTED_FOLDER_PATH] = path
        }
    }
    
    // 上次选择的主包路径
    val lastMainPackPath: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_MAIN_PACK_PATH]
        }
    
    suspend fun saveLastMainPackPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_MAIN_PACK_PATH] = path
        }
    }
    
    // Shizuku授权状态
    val shizukuAuthorized: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SHIZUKU_AUTHORIZED] ?: false
        }
    
    suspend fun setShizukuAuthorized(authorized: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHIZUKU_AUTHORIZED] = authorized
        }
    }
    
    // 自动停止应用
    val autoStopApp: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_STOP_APP] ?: true
        }
    
    suspend fun setAutoStopApp(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_STOP_APP] = enabled
        }
    }
    
    // 应用包名
    val appPackageName: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.APP_PACKAGE_NAME] ?: "com.tencent.tmgp.pubgmhd" // 默认和平精英包名
        }
    
    suspend fun setAppPackageName(packageName: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_PACKAGE_NAME] = packageName
        }
    }
    
    // 使用自动密码
    val useAutoPassword: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_AUTO_PASSWORD] ?: false
        }
    
    suspend fun setUseAutoPassword(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_AUTO_PASSWORD] = enabled
        }
    }
    
    // 上次解密密码
    val lastDecryptPassword: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_DECRYPT_PASSWORD]
        }
    
    suspend fun saveLastDecryptPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_DECRYPT_PASSWORD] = password
        }
    }
    
    // 锁定的时间
    val lockedTime: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LOCKED_TIME] ?: 0L
        }
    
    // 是否启用锁定时间
    val lockedTimeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LOCKED_TIME_ENABLED] ?: false
        }
    
    /**
     * 锁定当前时间
     */
    suspend fun lockTime(timeMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCKED_TIME] = timeMillis
            preferences[PreferencesKeys.LOCKED_TIME_ENABLED] = true
        }
    }
    
    /**
     * 解锁时间
     */
    suspend fun unlockTime() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCKED_TIME_ENABLED] = false
        }
    }
    
    /**
     * 获取锁定的时间（如果启用）
     */
    suspend fun getLockedTimeIfEnabled(): Long? {
        return context.dataStore.data.map { preferences ->
            if (preferences[PreferencesKeys.LOCKED_TIME_ENABLED] == true) {
                preferences[PreferencesKeys.LOCKED_TIME]
            } else {
                null
            }
        }.firstOrNull()
    }
    
    // 清除所有数据
    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}