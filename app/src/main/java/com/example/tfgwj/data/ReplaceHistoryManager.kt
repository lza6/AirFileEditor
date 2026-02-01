package com.example.tfgwj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.tfgwj.model.FileReplaceResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.replaceHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "replace_history")

class ReplaceHistoryManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ReplaceHistoryManager"
        private const val MAX_HISTORY_COUNT = 50
        private val HISTORY_KEY = stringPreferencesKey("replace_history")
        
        @Volatile
        private var instance: ReplaceHistoryManager? = null
        
        fun getInstance(context: Context): ReplaceHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ReplaceHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 获取替换历史记录
     */
    val history: Flow<List<ReplaceHistoryItem>> = context.replaceHistoryDataStore.data
        .map { preferences ->
            val historyJson = preferences[HISTORY_KEY] ?: "[]"
            try {
                parseHistoryJson(historyJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    
    /**
     * 解析历史记录JSON
     */
    private fun parseHistoryJson(json: String): List<ReplaceHistoryItem> {
        val items = mutableListOf<ReplaceHistoryItem>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                items.add(ReplaceHistoryItem.fromJson(jsonObject))
            }
        } catch (e: Exception) {
            // 忽略解析错误
        }
        return items
    }
    
    /**
     * 添加历史记录
     */
    suspend fun addHistory(item: ReplaceHistoryItem) {
        context.replaceHistoryDataStore.edit { preferences ->
            val currentHistory = preferences[HISTORY_KEY]?.let {
                parseHistoryJson(it)
            } ?: emptyList()
            
            // 添加新记录
            val newHistory = (listOf(item) + currentHistory).take(MAX_HISTORY_COUNT)
            
            // 保存
            preferences[HISTORY_KEY] = toJson(newHistory)
        }
    }
    
    /**
     * 转换为JSON字符串
     */
    private fun toJson(items: List<ReplaceHistoryItem>): String {
        val jsonArray = JSONArray()
        items.forEach { item ->
            jsonArray.put(item.toJson())
        }
        return jsonArray.toString()
    }
    
    /**
     * 清空历史记录
     */
    suspend fun clearHistory() {
        context.replaceHistoryDataStore.edit { preferences ->
            preferences.remove(HISTORY_KEY)
        }
    }
    
    /**
     * 删除指定记录
     */
    suspend fun deleteHistory(timestamp: Long) {
        context.replaceHistoryDataStore.edit { preferences ->
            val currentHistory = preferences[HISTORY_KEY]?.let {
                parseHistoryJson(it)
            } ?: emptyList()
            
            val newHistory = currentHistory.filter { it.timestamp != timestamp }
            preferences[HISTORY_KEY] = toJson(newHistory)
        }
    }
    
    /**
     * 创建历史记录项
     */
    fun createHistoryItem(
        packageName: String,
        sourcePath: String,
        targetPath: String,
        result: FileReplaceResult,
        backupPath: String? = null
    ): ReplaceHistoryItem {
        return ReplaceHistoryItem(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            sourcePath = sourcePath,
            targetPath = targetPath,
            totalFiles = result.totalFiles,
            successCount = result.successCount,
            failedCount = result.failedCount,
            errors = result.errors.map { "${it.filePath}: ${it.errorMessage}" },
            backupPath = backupPath
        )
    }
}

data class ReplaceHistoryItem(
    val timestamp: Long,
    val packageName: String,
    val sourcePath: String,
    val targetPath: String,
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int,
    val errors: List<String>,
    val backupPath: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("packageName", packageName)
        json.put("sourcePath", sourcePath)
        json.put("targetPath", targetPath)
        json.put("totalFiles", totalFiles)
        json.put("successCount", successCount)
        json.put("failedCount", failedCount)
        
        val errorsArray = JSONArray()
        errors.forEach { errorsArray.put(it) }
        json.put("errors", errorsArray)
        
        json.put("backupPath", backupPath)
        
        return json
    }
    
    fun getFormattedDate(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }
    
    fun isSuccess(): Boolean {
        return failedCount == 0 && successCount > 0
    }
    
    fun getSummary(): String {
        return if (isSuccess()) {
            "成功: $successCount 个文件"
        } else {
            "成功: $successCount, 失败: $failedCount"
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): ReplaceHistoryItem {
            val errorsList = mutableListOf<String>()
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null) {
                for (i in 0 until errorsArray.length()) {
                    errorsList.add(errorsArray.optString(i))
                }
            }
            
            return ReplaceHistoryItem(
                timestamp = json.optLong("timestamp", 0),
                packageName = json.optString("packageName", ""),
                sourcePath = json.optString("sourcePath", ""),
                targetPath = json.optString("targetPath", ""),
                totalFiles = json.optInt("totalFiles", 0),
                successCount = json.optInt("successCount", 0),
                failedCount = json.optInt("failedCount", 0),
                errors = errorsList,
                backupPath = if (json.isNull("backupPath")) null else json.optString("backupPath")
            )
        }
    }
}