package com.example.tfgwj.security

import android.content.Context
import android.util.Log

/**
 * 安全配置管理器
 *
 * 核心职责：
 * 1. 安全策略配置
 * 2. 运行时安全检测
 * 3. 安全日志记录
 *
 * @version V12.0.0 - Security Hardening
 */
object SecurityConfig {
    private const val TAG = "SecurityConfig"

    // 安全策略
    data class SecurityPolicy(
        val enablePathTraversalProtection: Boolean = true,
        val enableZipSlipProtection: Boolean = true,
        val enableCommandInjectionProtection: Boolean = true,
        val enableDebugDetection: Boolean = true,
        val enableRootDetection: Boolean = false, // 默认关闭，因为本应用需要 Root 功能
        val enableSignatureVerification: Boolean = true,
        val enableSensitiveLogMasking: Boolean = true,
        val maxPathLength: Int = 4096,
        val maxFileNameLength: Int = 255,
    )

    private var policy = SecurityPolicy()

    /**
     * 初始化安全配置
     */
    fun init(
        context: Context,
        customPolicy: SecurityPolicy? = null,
    ) {
        policy = customPolicy ?: SecurityPolicy()
        Log.i(TAG, "SecurityConfig initialized with policy: $policy")

        // 运行时安全检测
        if (policy.enableDebugDetection) {
            checkDebugger()
        }
    }

    /**
     * 获取当前安全策略
     */
    fun getPolicy(): SecurityPolicy = policy

    /**
     * 检测调试器连接
     */
    fun isDebuggerConnected(): Boolean {
        return android.os.Debug.isDebuggerConnected()
    }

    /**
     * 检测 Root 状态
     */
    fun isDeviceRooted(): Boolean {
        return com.example.tfgwj.utils.RootChecker.isRooted()
    }

    /**
     * 检测应用签名
     */
    fun verifyAppSignature(context: Context): Boolean {
        return try {
            val packageInfo =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES,
                )
            val signatures = packageInfo.signatures
            signatures != null && signatures.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify app signature", e)
            false
        }
    }

    /**
     * 安全日志记录（敏感信息脱敏）
     */
    fun secureLog(
        level: Int,
        tag: String,
        message: String,
    ) {
        val maskedMessage =
            if (policy.enableSensitiveLogMasking) {
                maskSensitiveInfo(message)
            } else {
                message
            }
        Log.println(level, tag, maskedMessage)
    }

    /**
     * 脱敏敏感信息
     */
    private fun maskSensitiveInfo(message: String): String {
        var masked = message

        // 脱敏路径中的用户名
        masked = masked.replace(Regex("/storage/emulated/\\d+/"), "/storage/emulated/****/")

        // 脱敏包名中的敏感部分
        masked = masked.replace(Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"), "***@***.***")

        // 脱敏密码
        masked = masked.replace(Regex("(?i)(password|passwd|pwd|secret|token|key)\\s*[=:]\\s*\\S+"), "$1=***")

        return masked
    }

    /**
     * 检查调试器
     */
    private fun checkDebugger() {
        if (isDebuggerConnected()) {
            Log.w(TAG, "Debugger detected!")
        }
    }

    /**
     * 安全异常处理
     */
    fun handleSecurityException(
        e: Exception,
        context: String,
    ) {
        Log.e(TAG, "Security exception in $context: ${e.message}")
        // 记录到安全日志
        // TODO: 可以发送到远程日志服务
    }
}
