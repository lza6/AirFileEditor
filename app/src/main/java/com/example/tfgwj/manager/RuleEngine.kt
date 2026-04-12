package com.example.tfgwj.manager

import android.content.Context
import android.util.Log
import com.example.tfgwj.model.CloudConfigResponse
import com.example.tfgwj.model.DynamicRule
import com.example.tfgwj.utils.AppLogger
import com.example.tfgwj.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * RuleEngine - V7.0.0 Network Layer Upgrade
 *
 * Upgraded from HttpURLConnection to OkHttp:
 * - Connection pooling for better performance
 * - HTTP/2 support
 * - Simplified async operations
 * - Better timeout management
 *
 * Responsibilities:
 * - Asynchronous concurrent fetching of remote JSON config
 * - Decouple hardcoded directory offset paths from code
 * - ETag-based cache efficiency
 */
object RuleEngine {
    private const val TAG = "RuleEngine"

    // Mirror nodes for rule fetching (V7.0.0: Using same racing mechanism as V5 OTA)
    private val CLOUD_RULE_URLS =
        listOf(
            "https://ghproxy.net/https://raw.githubusercontent.com/lza6/AirFileEditor/main/rules.json",
            "https://mirror.ghproxy.com/https://raw.githubusercontent.com/lza6/AirFileEditor/main/rules.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/lza6/AirFileEditor/main/rules.json",
            "https://raw.githubusercontent.com/lza6/AirFileEditor/main/rules.json",
        )

    private var cacheFile: File? = null
    private var lastETag: String? = null

    // OkHttp client - initialized lazily
    private var okHttpClient: okhttp3.OkHttpClient? = null

    fun init(context: Context) {
        cacheFile = File(context.cacheDir, "cloud_rules.json")
        // Initialize OkHttpClient with cache
        okHttpClient = NetworkClient.getClient(context.cacheDir)
        // Preload from cache
        loadFromCache()
    }

    var currentRule: DynamicRule? = null
        private set

    /**
     * Asynchronous concurrent fetching and parsing rules
     * V7.0.0: Upgraded to use OkHttp
     */
    suspend fun fetchCloudRules(): CloudConfigResponse? =
        withContext(Dispatchers.IO) {
            AppLogger.action("Cloud Rules", "开始并发拉取远端动态规则 (OkHttp)...")

            try {
                coroutineScope {
                    val resultChannel = Channel<String?>(CLOUD_RULE_URLS.size)
                    val client = okHttpClient ?: NetworkClient.getClient()

                    val jobs =
                        CLOUD_RULE_URLS.map { urlStr ->
                            launch {
                                try {
                                    // V7.0.0: Use NetworkClient for consistent request building
                                    val requestBuilder = NetworkClient.createRequest(urlStr, lastETag)
                                    val request = requestBuilder.build()

                                    // Execute with OkHttp (synchronous in coroutine context)
                                    val response = client.newCall(request).execute()

                                    when {
                                        response.code == 200 -> {
                                            val etag = response.header("ETag")
                                            if (etag != null) lastETag = etag

                                            val jsonText = response.body?.string()
                                            if (jsonText != null) {
                                                resultChannel.send(jsonText)
                                            } else {
                                                resultChannel.send(null)
                                            }
                                        }
                                        response.code == 304 -> {
                                            AppLogger.action("RuleEngine", "未变动 (304)，沿用缓存")
                                            resultChannel.send("USE_CACHE")
                                        }
                                        else -> {
                                            resultChannel.send(null)
                                        }
                                    }
                                    response.close()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Fetch failed for $urlStr: ${e.message}")
                                    resultChannel.send(null)
                                }
                            }
                        }

                    // Get first successful response
                    var jsonResult: String? = null
                    for (i in CLOUD_RULE_URLS.indices) {
                        val res = resultChannel.receive()
                        if (res != null) {
                            jsonResult = res
                            // Cancel remaining coroutines
                            jobs.forEach { it.cancel() }
                            break
                        }
                    }
                    resultChannel.close()

                    if (jsonResult == "USE_CACHE") {
                        return@coroutineScope null
                    }

                    if (jsonResult == null) {
                        AppLogger.action("Cloud Rules", "全域镜像节点拉取规则失败，启用本地容灾 Fallback")
                        return@coroutineScope null
                    }

                    // Write to cache for persistence
                    saveToCache(jsonResult)

                    // Parse and apply
                    return@coroutineScope parseAndApply(jsonResult)
                }
            } catch (e: Exception) {
                AppLogger.action("Cloud Rules", "规则引擎挂起异常: ${e.message}")
                null
            }
        }

    private fun parseAndApply(jsonResult: String): CloudConfigResponse? {
        try {
            val jsonObject = JSONObject(jsonResult)
            val rulesArray = jsonObject.optJSONArray("rules") ?: return null

            val rules = mutableListOf<DynamicRule>()
            for (i in 0 until rulesArray.length()) {
                val ruleObj = rulesArray.optJSONObject(i) ?: continue
                rules.add(
                    DynamicRule(
                        version = ruleObj.optString("version", "1.0"),
                        targetPackage = ruleObj.optString("target_package", ""),
                        configOffsetPath = ruleObj.optString("config_offset_path", ""),
                        enabled = ruleObj.optBoolean("enabled", false),
                        description = ruleObj.optString("description", ""),
                    ),
                )
            }

            val config = CloudConfigResponse(rules)
            if (config.rules.isNotEmpty()) {
                currentRule = config.rules.firstOrNull { it.enabled }
                AppLogger.action("Cloud Rules", "远端规则解析成功: 拦截包名 [${currentRule?.targetPackage}]")
            }
            return config
        } catch (e: Exception) {
            AppLogger.action("Cloud Rules", "JSON 解析异常: ${e.message}")
            return null
        }
    }

    private fun loadFromCache() {
        cacheFile?.let { file ->
            if (file.exists()) {
                try {
                    val json = file.readText()
                    parseAndApply(json)
                    AppLogger.action("RuleEngine", "从缓存预加载成功")
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun saveToCache(json: String) {
        try {
            cacheFile?.writeText(json)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Clear cached rules (for force refresh)
     * V7.0.0: Added method
     */
    fun clearCache() {
        lastETag = null
        cacheFile?.delete()
        currentRule = null
        AppLogger.action("RuleEngine", "缓存已清除")
    }

    /**
     * Get current ETag for debugging
     * V7.0.0: Added method
     */
    fun getCurrentETag(): String? = lastETag
}
