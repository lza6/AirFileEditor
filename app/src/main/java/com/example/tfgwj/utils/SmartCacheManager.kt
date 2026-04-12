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

    /**
     * 清理环境：删除 Saved 目录下除白名单外的所有内容
     * 白名单：Paks, PandoraV2, ImageDownloadV3
     *
     * 智能选择清理方式（动态路由）：
     * - 如果有 Root 权限 → 使用 Root 命令删除
     * - 如果可以直接访问私有目录（无需 Shizuku 且无 Root 模式）→ 使用原生 API 删除
     * - 如果需要 Shizuku 权限 → 使用 Shizuku shell 命令删除
     */
    suspend fun cleanEnvironment(
        context: Context,
        packageName: String,
        shizukuManager: ShizukuManager?,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)? = null,
    ): Result<Int> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val whiteList = listOf("Paks", "PandoraV2", "ImageDownloadV3")
            val savedPath = "/storage/emulated/0/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved"

            // 1. 获取全能模式下的可用序列
            val envStatus = PermissionChecker.checkPermissionAccess(packageName, stopAppFirst = false)
            val modes = envStatus.availableModes

            Log.d(TAG, "📦 [环境清理] 开始流程，模式序列: $modes")

            var lastError: Exception? = null

            // 2. 按优先级尝试清理
            for (mode in modes) {
                Log.i(TAG, "正在尝试清理模式: $mode")
                try {
                    when (mode) {
                        PermissionChecker.AccessMode.ROOT -> {
                            val count = cleanViaRoot(savedPath, whiteList, progressCallback)
                            if (count >= 0) {
                                AppLogger.func("cleanEnvironment", "Root 模式成功", true)
                                return@withContext Result.success(count)
                            }
                        }
                        PermissionChecker.AccessMode.NATIVE -> {
                            val count = cleanViaNative(savedPath, whiteList, progressCallback)
                            if (count >= 0) {
                                AppLogger.func("cleanEnvironment", "Native 模式成功", true)
                                return@withContext Result.success(count)
                            }
                        }
                        PermissionChecker.AccessMode.SHIZUKU -> {
                            if (shizukuManager?.isServiceConnected?.value == true) {
                                val count = cleanViaShizuku(shizukuManager, savedPath, whiteList.toTypedArray(), progressCallback)
                                if (count >= 0) {
                                    AppLogger.func("cleanEnvironment", "Shizuku 模式成功", true)
                                    return@withContext Result.success(count)
                                }
                            }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "清理模式 $mode 失败: ${e.message}")
                }
            }

            return@withContext Result.failure(lastError ?: Exception("所有权限模式均无法完成清理"))
        }

    suspend fun checkAndOptimize(
        context: Context,
        packageName: String,
        shizukuManager: ShizukuManager? = null,
    ): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val start = System.currentTimeMillis()
            var result: String? = null

            try {
                val savedPath = "/storage/emulated/0/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved"
                val envStatus = PermissionChecker.checkPermissionAccess(packageName, stopAppFirst = false)
                val modes = envStatus.availableModes

                for (mode in modes) {
                    try {
                        result =
                            when (mode) {
                                PermissionChecker.AccessMode.ROOT,
                                PermissionChecker.AccessMode.NATIVE,
                                -> {
                                    val savedDir = File(savedPath)
                                    if (savedDir.exists()) performDirectOptimize(savedDir) else null
                                }
                                PermissionChecker.AccessMode.SHIZUKU -> {
                                    if (shizukuManager?.isServiceConnected?.value == true) {
                                        performShizukuOptimize(shizukuManager, savedPath)
                                    } else {
                                        null
                                    }
                                }
                                else -> null
                            }
                        if (result != null) break
                    } catch (e: Exception) {
                        Log.w(TAG, "优化模式 $mode 失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "智能优化流程异常", e)
            }

            val duration = System.currentTimeMillis() - start
            AppLogger.func("checkAndOptimize", "智能检测完成", true, "耗时: ${duration}ms | 结果: $result")
            result
        }

    /**
     * 使用 Root 命令清理（适用于 Root 设备）
     */
    private fun cleanViaRoot(
        savedPath: String,
        whiteList: List<String>,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)?,
    ): Int {
        val rootManagerType = RootChecker.getRootManagerType()
        Log.d(TAG, "========== Root 模式清理环境 ==========")
        Log.d(TAG, "模式类型: Root 模式 ($rootManagerType)")
        Log.d(TAG, "目标目录: $savedPath")
        Log.d(TAG, "白名单: ${whiteList.joinToString(", ")}")

        // 使用 Root 命令列出目录内容
        val listCommand = "ls -1 \"$savedPath\""
        val listResult = RootChecker.executeRootCommand(listCommand)
        Log.d(TAG, "列出目录命令: $listCommand")
        Log.d(TAG, "列出目录结果: $listResult")

        if (listResult == null || listResult.isEmpty()) {
            Log.w(TAG, "目录为空或无法访问")
            AppLogger.func("cleanViaRoot", "目录为空或无法访问", false, savedPath)
            return 0
        }

        // 解析文件列表
        val items = listResult.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val toDelete =
            items.filter { item ->
                !whiteList.any { it.equals(item, ignoreCase = true) }
            }

        val total = toDelete.size
        var deletedCount = 0

        Log.d(TAG, "待删除项: $total 个")
        toDelete.forEachIndexed { index, item ->
            progressCallback?.invoke(index + 1, total, item)
            try {
                val deleteCommand = "rm -rf \"$savedPath/$item\""
                val deleteResult = RootChecker.executeRootCommand(deleteCommand)
                Log.d(TAG, "✅ [Root 命令] 删除文件/文件夹: $item")
                deletedCount++
            } catch (e: Exception) {
                Log.e(TAG, "❌ [Root 命令] 删除失败: $item", e)
                AppLogger.e("cleanViaRoot", "删除失败: $item", e)
            }
        }

        Log.i(TAG, "✅ Root 模式清理完成：成功删除 $deletedCount 项")
        AppLogger.func("cleanViaRoot", "Root 清理完成", true, "删除 $deletedCount 项")
        return deletedCount
    }

    /**
     * 使用原生 API 清理（适用于 root 或可直接访问私有目录的机型）
     */
    private fun cleanViaNative(
        savedPath: String,
        whiteList: List<String>,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)?,
    ): Int {
        val savedDir = File(savedPath)
        if (!savedDir.exists()) {
            Log.d(TAG, "ℹ️ 目录不存在，无需清理: $savedPath")
            return 0
        }

        if (!savedDir.isDirectory) {
            Log.w(TAG, "⚠️ 路径不是目录: $savedPath")
            return 0
        }

        val items =
            savedDir.listFiles() ?: run {
                Log.e(TAG, "❌ 无法列出目录内容: $savedPath")
                return 0
            }

        val toDelete =
            items.filter { file ->
                !whiteList.any { it.equals(file.name, ignoreCase = true) }
            }

        val total = toDelete.size
        var deletedCount = 0

        Log.d(TAG, "🗑️ 准备清理 $total 个项目")

        toDelete.forEachIndexed { index, file ->
            val itemName = file.name
            Log.d(TAG, "正在删除 [$index/$total]: $itemName")
            progressCallback?.invoke(index + 1, total, itemName)

            try {
                if (file.isDirectory) {
                    if (file.deleteRecursively()) {
                        deletedCount++
                        Log.d(TAG, "✅ 成功删除目录: $itemName")
                    } else {
                        Log.w(TAG, "❌ 删除目录失败: $itemName")
                    }
                } else {
                    if (file.delete()) {
                        deletedCount++
                        Log.d(TAG, "✅ 成功删除文件: $itemName")
                    } else {
                        Log.w(TAG, "❌ 删除文件失败: $itemName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 清理过程发生异常: $itemName", e)
                AppLogger.e("cleanViaNative", "删除异常: $itemName", e)
            }
        }

        Log.i(TAG, "✅ 原生清理完成，共删除 $deletedCount 项")
        AppLogger.func("cleanViaNative", "原生清理完成", true, "删除 $deletedCount 项")
        return deletedCount
    }

    /**
     * 使用 Shizuku shell 命令清理（适用于需要 Shizuku 权限的机型）
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun cleanViaShizuku(
        shizukuManager: ShizukuManager,
        savedPath: String,
        whiteList: Array<String>,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)?,
    ): Int =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            shizukuManager.cleanDirectoryWithProgress(
                savedPath,
                whiteList,
                object : com.example.tfgwj.IDeleteCallback.Stub() {
                    override fun onProgress(
                        current: Int,
                        total: Int,
                        currentItem: String?,
                    ) {
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
                },
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

    private fun performShizukuOptimize(
        shizukuManager: ShizukuManager,
        savedPath: String,
    ): String? {
        try {
            // 检查 .pixuicache 是否存在
            if (shizukuManager.fileExists("$savedPath/.pixuicache")) return null

            // 检查 pixuicache 是否存在
            if (shizukuManager.fileExists("$savedPath/pixuicache")) {
                // 使用 shell 命令执行重命名
                val exitCode = shizukuManager.executeCommand("mv \"$savedPath/pixuicache\" \"$savedPath/.pixuicache\"")
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
