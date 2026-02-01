package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.tfgwj.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.*

/**
 * 文件替换 Worker
 * 使用 WorkManager 在后台执行大文件替换任务
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileReplaceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FileReplaceWorker"
        
        // 输入参数键
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_TARGET_PACKAGE = "target_package"
        
        // 进度键
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR_MESSAGE = "error_message"  // 错误信息键
        const val KEY_FAILED_FILES = "failed_files"  // 失败文件列表键
        
        // 分块大小 (64KB)
        const val BUFFER_SIZE = 64 * 1024
        
        /**
         * 创建工作请求
         */
        fun createWorkRequest(sourcePath: String, targetPackage: String): OneTimeWorkRequest {
            val inputData = Data.Builder()
                .putString(KEY_SOURCE_PATH, sourcePath)
                .putString(KEY_TARGET_PACKAGE, targetPackage)
                .build()
            
            return OneTimeWorkRequestBuilder<FileReplaceWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourcePath = inputData.getString(KEY_SOURCE_PATH) ?: return@withContext Result.failure()
        val targetPackage = inputData.getString(KEY_TARGET_PACKAGE) ?: return@withContext Result.failure()
        
        Log.d(TAG, "开始后台替换: $sourcePath -> $targetPackage")
        
        // 声明计数变量
        var processed = 0
        var totalFiles = 0
        val failedFiles = mutableListOf<String>()  // 失败文件列表
        
        try {
            val sourceDir = File(sourcePath)
            if (!sourceDir.exists()) {
                Log.e(TAG, "源目录不存在")
                return@withContext Result.failure()
            }
            
            // 查找 Android 目录
            val androidDir = File(sourceDir, "Android")
            if (!androidDir.exists()) {
                Log.e(TAG, "Android 目录不存在: $sourcePath/Android")
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "源文件夹中没有 Android 目录")
                )
            }
            
            // 目标路径
            val targetBase = "/storage/emulated/0/Android"
            
            // 优化逻辑：检查是否可以使用 Shizuku 高性能批量复制
            val shizukuManager = ShizukuManager.getInstance(applicationContext)
            var shizukuOptimizedSuccess = false
            
            // 等待 Shizuku 服务连接（最多 3 秒）
            // 防止冷启动时服务还没连接好就降级到慢速模式
            if (shizukuManager.isAuthorized.value && !shizukuManager.isServiceConnected.value) {
                Log.d(TAG, "等待 Shizuku 服务连接...")
                repeat(30) { // 每 100ms 检查一次，最多等 3 秒
                    if (shizukuManager.isServiceConnected.value) {
                        Log.d(TAG, "Shizuku 服务已连接")
                        return@repeat
                    }
                    kotlinx.coroutines.delay(100)
                }
            }
            
            if (shizukuManager.isAuthorized.value && shizukuManager.isServiceConnected.value) {
                Log.d(TAG, "使用 Shizuku 高性能优化模式")
                
                try {
                    // 使用 suspendCancellableCoroutine 等待回调完成
                    // 注意：sourcePath 是主包根目录，我们需要复制其下的 Android 目录内容
                    // 使用 androidDir.absolutePath 作为源，这样 cp source/. target/ 才能正确合并内容
                    shizukuOptimizedSuccess = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        shizukuManager.copyDirectoryWithProgress(
                            androidDir.absolutePath,  // 修复：使用 Android 子目录作为源，而非主包根目录
                            targetBase,
                            object : com.example.tfgwj.ICopyCallback.Stub() {
                                override fun onProgress(current: Int, total: Int, currentFile: String?) {
                                    if (cont.isActive) {
                                        // current < 0 表示这是一个错误回调
                                        if (current < 0 && currentFile != null) {
                                            Log.e(TAG, "复制失败文件: $currentFile")
                                            // 收集失败的文件名（去除 [失败] 前缀）
                                            val fileName = currentFile.removePrefix("[失败] ").trim()
                                            if (fileName.isNotEmpty() && !failedFiles.contains(fileName)) {
                                                failedFiles.add(fileName)
                                            }
                                        }
                                        
                                        // 使用绝对值计算进度
                                        val actualCurrent = kotlin.math.abs(current)
                                        setProgressAsync(workDataOf(
                                            KEY_PROGRESS to (if (total > 0) actualCurrent * 100 / total else 0),
                                            KEY_CURRENT_FILE to (currentFile ?: ""),
                                            KEY_PROCESSED to actualCurrent,
                                            KEY_TOTAL to total
                                        ))
                                    }
                                }

                                override fun onCompleted(successCount: Int) {
                                    if (cont.isActive) {
                                        processed = successCount
                                        // 即使有失败的文件，只要成功复制了文件，就视为成功
                                        if (failedFiles.isNotEmpty()) {
                                            Log.w(TAG, "复制完成但有 ${failedFiles.size} 个文件失败: ${failedFiles.joinToString(", ")}")
                                        }
                                        cont.resume(true, null)
                                    }
                                }

                                override fun onError(message: String?) {
                                    Log.e(TAG, "Shizuku 优化复制出错: $message")
                                    if (cont.isActive) {
                                        // 即使遇到错误，只要复制了文件，就视为部分成功
                                        // 这样不会触发降级到慢速模式
                                        if (processed > 0 || failedFiles.isNotEmpty()) {
                                            Log.w(TAG, "虽然有错误但已复制 $processed 个文件，继续执行")
                                            cont.resume(true, null)
                                        } else {
                                            // 完全没有复制任何文件才返回失败
                                            cont.resume(false, null)
                                        }
                                    }
                                }
                            }
                        )
                    }
                    
                    // 在结果中包含失败文件列表
                    if (failedFiles.isNotEmpty()) {
                        Log.i(TAG, "失败文件列表: ${failedFiles.joinToString("; ")}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku 优化模式异常", e)
                    shizukuOptimizedSuccess = false
                }
            }
            
            if (shizukuOptimizedSuccess) {
                Log.d(TAG, "Shizuku 批量复制完成")
            } else {
                // 不再降级到慢速模式，直接返回失败
                Log.e(TAG, "Shizuku 批量复制失败")
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "需要 Shizuku 高性能模式才能替换，请确保 Shizuku 已连接")
                )
            }
            
            Log.d(TAG, "替换完成: $processed 个文件")
            
            // 构建结果数据
            val resultBuilder = Data.Builder()
                .putInt(KEY_PROCESSED, processed)
                .putInt(KEY_TOTAL, totalFiles)
            
            // 如果有失败的文件，添加到结果中
            if (failedFiles.isNotEmpty()) {
                Log.w(TAG, "共有 ${failedFiles.size} 个文件复制失败: ${failedFiles.joinToString(", ")}")
                // 将失败文件列表转换为 JSON 字符串
                val jsonArray = JSONArray()
                failedFiles.forEach { jsonArray.put(it) }
                resultBuilder.putString(KEY_FAILED_FILES, jsonArray.toString())
            }
            
            Result.success(resultBuilder.build())
            
        } catch (e: Exception) {
            Log.e(TAG, "替换失败", e)
            Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "替换失败: ${e.message}")
            )
        }
    }
    
    /**
     * 分块复制文件（避免大文件内存溢出）
     * 使用自适应缓冲区大小优化大文件性能
     */
    private fun copyFileChunked(source: File, target: File) {
        target.parentFile?.mkdirs()
        
        // 自适应缓冲区大小
        val bufferSize = when {
            source.length() > 100 * 1024 * 1024 -> 1024 * 1024 // 100MB+ → 1MB buffer
            source.length() > 10 * 1024 * 1024 -> 512 * 1024   // 10MB+ → 512KB buffer
            else -> 64 * 1024                                   // 其他 → 64KB buffer
        }
        
        BufferedInputStream(FileInputStream(source), bufferSize).use { bis ->
            BufferedOutputStream(FileOutputStream(target), bufferSize).use { bos ->
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    bos.write(buffer, 0, bytesRead)
                }
                
                bos.flush()
            }
        }
        
        // 保持原文件时间
        target.setLastModified(source.lastModified())
    }
    
    /**
     * 递归收集文件
     */
    private fun collectFiles(dir: File, list: MutableList<File>) {
        list.add(dir)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                collectFiles(child, list)
            }
        }
    }
}
