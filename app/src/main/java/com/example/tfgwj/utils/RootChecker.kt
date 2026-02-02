package com.example.tfgwj.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root 权限检测工具类
 */
object RootChecker {
    private const val TAG = "RootChecker"
    
    private var cachedIsRooted: Boolean? = null
    private var rootCheckFailedCount = 0

    /**
     * 检测是否具有 Root 权限
     * 增加缓存和真机执行测试
     */
    fun isRooted(): Boolean {
        // 如果连续失败超过 3 次且检测为无 Root，则不再重复尝试昂贵的操作
        if (cachedIsRooted == false && rootCheckFailedCount > 3) {
            return false
        }
        
        cachedIsRooted?.let { return it }

        // 优先尝试执行测试，这是最准的
        val isTrulyRooted = checkRootCommand()
        if (isTrulyRooted) {
            cachedIsRooted = true
            return true
        }

        // 备选：检查文件
        if (checkRootFiles()) {
            cachedIsRooted = true
            return true
        }
        
        Log.d(TAG, "Root 检测: 未检测到有效 Root 权限")
        cachedIsRooted = false
        rootCheckFailedCount++
        return false
    }
    
    /**
     * 检查是否可以执行指定命令
     */
    private fun canExecuteCommand(command: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("which", command))
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查常见的 Root 相关文件
     */
    private fun checkRootFiles(): Boolean {
        val rootFiles = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )
        
        for (file in rootFiles) {
            if (File(file).exists()) {
                Log.d(TAG, "发现 Root 文件: $file")
                return true
            }
        }
        
        return false
    }
    
    /**
     * 尝试执行需要 root 的命令
     */
    private fun checkRootCommand(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor()
            
            // 检查输出中是否包含 uid=0
            output.contains("uid=0")
        } catch (e: java.io.IOException) {
            if (e.message?.contains("Operation not permitted") == true) {
                Log.w(TAG, "su 执行受限: Operation not permitted")
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    fun executeRootCommand(command: String): String? {
        // 如果已知没有权限或受限，不再尝试
        if (cachedIsRooted == false) return null
        
        return try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            val inputStream = BufferedReader(InputStreamReader(process.inputStream))
            val errorStream = BufferedReader(InputStreamReader(process.errorStream))
            
            outputStream.write(command.toByteArray())
            outputStream.write("\nexit\n".toByteArray())
            outputStream.flush()
            
            val output = StringBuilder()
            var line: String?
            while (inputStream.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            val error = StringBuilder()
            while (errorStream.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }
            
            outputStream.close()
            inputStream.close()
            errorStream.close()
            process.waitFor()
            
            if (error.isNotEmpty()) {
                Log.w(TAG, "Root 命令输出(错误/警告): $error")
            }
            
            output.toString()
        } catch (e: java.io.IOException) {
            if (e.message?.contains("Operation not permitted") == true) {
                Log.e(TAG, "执行 Root 命令失败: 权限不足 (Operation not permitted)")
                cachedIsRooted = false // 修正缓存
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "执行 Root 命令异常", e)
            null
        }
    }
    
    /**
     * 检查 Magisk 是否已安装
     */
    fun isMagiskInstalled(): Boolean {
        return File("/data/adb/magisk").exists() || 
               File("/sbin/magisk").exists() ||
               File("/system/bin/magisk").exists()
    }
    
    /**
     * 获取 Root 管理器类型
     */
    fun getRootManagerType(): String {
        return when {
            isMagiskInstalled() -> "Magisk"
            File("/system/app/Superuser.apk").exists() -> "SuperSU"
            File("/system/xbin/su").exists() -> "Chainfire SuperSU"
            File("/system/app/Supersu.apk").exists() -> "SuperSU"
            else -> "Unknown"
        }
    }
}