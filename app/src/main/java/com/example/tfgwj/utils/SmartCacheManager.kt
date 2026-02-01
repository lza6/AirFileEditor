package com.example.tfgwj.utils

import android.content.Context
import android.util.Log
import com.example.tfgwj.shizuku.ShizukuManager
import java.io.File

/**
 * 智能缓存管理器
 * 用于检测并优化游戏缓存文件夹 (.pixuicache)
 */
object SmartCacheManager {
    private const val TAG = "SmartCacheManager"
    private const val SAVED_PATH = "/storage/emulated/0/Android/data/com.tencent.tmgp.pubgmhd/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved"

    /**
     * 检测并执行优化
     * @param context 上下文
     * @param shizukuManager Shizuku 管理器
     * @return 状态消息
     */
    fun checkAndOptimize(context: Context, shizukuManager: ShizukuManager? = null): String? {
        val start = System.currentTimeMillis()
        var result: String? = null
        
        try {
            // 1. 优先尝试直接访问（原生 File API）
            val savedDir = File(SAVED_PATH)
            if (savedDir.exists()) {
                result = performDirectOptimize(savedDir)
            }
            
            // 2. 如果原生方式没能处理（可能没找到文件夹或没权限），且 Shizuku 可用，则通过 Shizuku 处理
            if (result == null && shizukuManager?.isServiceConnected?.value == true) {
                result = performShizukuOptimize(shizukuManager)
            }
        } catch (e: Exception) {
            AppLogger.func("checkAndOptimize", "智能检测失败", false, e.message ?: "未知错误")
        }
        
        val duration = System.currentTimeMillis() - start
        AppLogger.func("checkAndOptimize", "智能检测完成", true, "耗时: ${duration}ms | 结果: $result")
        return result
    }

    /**
     * 清理环境：删除 Saved 目录下除白名单外的所有内容
     * 白名单：Paks, PandoraV2, ImageDownloadV3
     * 
     * 智能选择清理方式：
     * - 如果原生 API 可以访问私有目录（root/特殊机型）→ 使用原生删除
     * - 如果需要 Shizuku 权限 → 使用 Shizuku shell 命令删除
     */
    suspend fun cleanEnvironment(
        context: Context, 
        shizukuManager: ShizukuManager?,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)? = null
    ): Result<Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        
        val whiteList = listOf("Paks", "PandoraV2", "ImageDownloadV3")
        
        // 1. 智能检测：是否需要 Shizuku
        val checkResult = PermissionChecker.checkPermissionAccess(stopAppFirst = true)
        AppLogger.func("cleanEnvironment", "权限检测", true, 
            "needsShizuku=${checkResult.needsShizuku}, canAccessDirectly=${checkResult.canAccessDirectly}")
        
        try {
            if (checkResult.canAccessDirectly) {
                // 2a. 可以直接访问 → 使用原生 API 删除
                AppLogger.func("cleanEnvironment", "使用原生 API 清理", true)
                val deletedCount = cleanViaNative(whiteList, progressCallback)
                Result.success(deletedCount)
                
            } else {
                // 2b. 需要 Shizuku → 检查 Shizuku 是否可用
                if (shizukuManager?.isServiceConnected?.value != true) {
                    return@withContext Result.failure(Exception("需要 Shizuku 授权才能清理环境"))
                }
                
                AppLogger.func("cleanEnvironment", "使用 Shizuku shell 清理", true)
                val deletedCount = cleanViaShizuku(shizukuManager, whiteList.toTypedArray(), progressCallback)
                if (deletedCount >= 0) {
                    Result.success(deletedCount)
                } else {
                    Result.failure(Exception("清理失败"))
                }
            }
        } catch (e: Exception) {
            AppLogger.func("cleanEnvironment", "清理异常", false, e.message ?: "未知错误")
            Result.failure(e)
        }
    }
    
    /**
     * 使用原生 API 清理（适用于 root 或可直接访问私有目录的机型）
     */
    private fun cleanViaNative(
        whiteList: List<String>,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)?
    ): Int {
        val savedDir = File(SAVED_PATH)
        if (!savedDir.exists() || !savedDir.isDirectory) {
            AppLogger.func("cleanViaNative", "目录不存在", false, SAVED_PATH)
            return 0
        }
        
        val items = savedDir.listFiles() ?: return 0
        val toDelete = items.filter { file ->
            !whiteList.any { it.equals(file.name, ignoreCase = true) }
        }
        
        val total = toDelete.size
        var deletedCount = 0
        
        toDelete.forEachIndexed { index, file ->
            progressCallback?.invoke(index + 1, total, file.name)
            try {
                if (file.deleteRecursively()) {
                    deletedCount++
                }
            } catch (e: Exception) {
                AppLogger.e("cleanViaNative", "删除失败: ${file.name}", e)
            }
        }
        
        AppLogger.func("cleanViaNative", "原生清理完成", true, "删除 $deletedCount 项")
        return deletedCount
    }
    
    /**
     * 使用 Shizuku shell 命令清理（适用于需要 Shizuku 权限的机型）
     */
    private suspend fun cleanViaShizuku(
        shizukuManager: ShizukuManager,
        whiteList: Array<String>,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)?
    ): Int = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        shizukuManager.cleanDirectoryWithProgress(
            SAVED_PATH,
            whiteList,
            object : com.example.tfgwj.IDeleteCallback.Stub() {
                override fun onProgress(current: Int, total: Int, currentItem: String?) {
                    progressCallback?.invoke(current, total, currentItem ?: "")
                }
                
                override fun onCompleted(deletedCount: Int) {
                    if (cont.isActive) {
                        AppLogger.func("cleanViaShizuku", "Shizuku 清理完成", true, "删除 $deletedCount 项")
                        cont.resume(deletedCount, null)
                    }
                }
                
                override fun onError(message: String?) {
                    if (cont.isActive) {
                        AppLogger.func("cleanViaShizuku", "Shizuku 清理失败", false, message ?: "未知错误")
                        cont.resume(-1, null)
                    }
                }
            }
        )
    }



    private fun performDirectOptimize(savedDir: File): String? {
        val dotPixui = File(savedDir, ".pixuicache")
        if (dotPixui.exists()) return null // 已存在 .pixuicache，无需操作

        val pixui = File(savedDir, "pixuicache")
        if (pixui.exists()) {
            // 确保没有 .pixuicache 冲突
            if (pixui.renameTo(dotPixui)) {
                return "已将 pixuicache 智能重命名为 .pixuicache"
            }
        }
        return null
    }

    private fun performShizukuOptimize(shizukuManager: ShizukuManager): String? {
        try {
            // 检查 .pixuicache 是否存在
            if (shizukuManager.fileExists("$SAVED_PATH/.pixuicache")) return null
            
            // 检查 pixuicache 是否存在
            if (shizukuManager.fileExists("$SAVED_PATH/pixuicache")) {
                // 使用 shell 命令执行重命名
                val exitCode = shizukuManager.executeCommand("mv \"$SAVED_PATH/pixuicache\" \"$SAVED_PATH/.pixuicache\"")
                if (exitCode == 0) {
                    return "已通过 Shizuku 将 pixuicache 重命名为 .pixuicache"
                }
            }
        } catch (e: Exception) {
            AppLogger.func("performShizukuOptimize", "Shizuku 检测失败", false, e.message ?: "未知错误")
        }
        return null
    }
}
