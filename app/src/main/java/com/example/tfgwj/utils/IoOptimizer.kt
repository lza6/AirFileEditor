package com.example.tfgwj.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * IO 优化器
 * 集成 NIO Zero-Copy、动态并发控制、增量更新检测等技术
 */
object IoOptimizer {
    private const val TAG = "IoOptimizer"
    
    // 动态并发度：CPU 核心数
    private val CORE_COUNT = Runtime.getRuntime().availableProcessors()
    private val MAX_CONCURRENCY = CORE_COUNT.coerceAtLeast(4)
    
    // 缓冲区池 (对象池) - 减少密集 IO 时的 GC 压力
    private val BUFFER_POOL = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private const val POOL_SIZE = 8
    
    /**
     * 获取最佳缓冲区大小 (基于当前可用内存)
     */
    fun getOptimalBufferSize(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        
        return when {
            maxMemoryMB < 128 -> 128 * 1024      // < 128MB: 128KB
            maxMemoryMB < 256 -> 256 * 1024      // 128-256MB: 256KB
            maxMemoryMB < 512 -> 512 * 1024      // 256-512MB: 512KB
            else -> 1024 * 1024                   // >= 512MB: 1MB
        }
    }

    /**
     * 从池中获取一个缓冲区
     */
    fun acquireBuffer(): ByteArray {
        return BUFFER_POOL.poll() ?: ByteArray(getOptimalBufferSize())
    }

    /**
     * 将缓冲区归还池中
     */
    fun releaseBuffer(buffer: ByteArray) {
        if (BUFFER_POOL.size < POOL_SIZE) {
            BUFFER_POOL.offer(buffer)
        }
    }
    
    /**
     * 快速复制文件 (NIO Zero-Copy)
     */
    fun fastCopy(source: File, target: File): Boolean {
        return try {
            if (!target.parentFile?.exists()!!) {
                target.parentFile?.mkdirs()
            }
            
            FileInputStream(source).channel.use { sourceChannel ->
                FileOutputStream(target).channel.use { targetChannel ->
                    val size = sourceChannel.size()
                    var transferred: Long = 0
                    while (transferred < size) {
                        transferred += sourceChannel.transferTo(transferred, size - transferred, targetChannel)
                    }
                }
            }
            // 保持修改时间一致，便于后续增量校验
            target.setLastModified(source.lastModified())
            true
        } catch (e: Exception) {
            Log.e(TAG, "FastCopy 失败: ${source.name}", e)
            false
        }
    }
    
    /**
     * 增量检测：判断文件是否需要更新
     */
    fun needsUpdate(source: File, target: File): Boolean {
        if (!target.exists()) return true
        if (source.length() != target.length()) return true
        
        // 如果文件大小相同且修改时间完全一致，大概率是同一个文件
        if (source.lastModified() == target.lastModified()) return false
        
        // 进一步校验：如果是小文件（< 5MB），做快速 MD5 校验
        if (source.length() < 5 * 1024 * 1024) {
            return !FileHasher.areFilesEqual(source, target)
        }
        
        // 大文件且时间不一致，为了安全起见认为需要更新
        return true
    }
    
    /**
     * 并行处理文件列表
     * @param items 要处理的项
     * @param action 处理逻辑
     * @param progressCallback 进度回调 (已完成, 总数, 当前项名称)
     */
    suspend fun <T> parallelProcess(
        items: List<T>,
        action: suspend (T) -> Boolean,
        progressCallback: ((Int, Int, String) -> Unit)? = null
    ): ProcessResult = coroutineScope {
        val total = items.size
        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENCY)
        
        val deferreds = items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val success = action(item)
                    val currentSuccess = if (success) successCount.incrementAndGet() else successCount.get()
                    val currentFailed = if (!success) failedCount.incrementAndGet() else failedCount.get()
                    
                    val itemName = when (item) {
                        is File -> item.name
                        is String -> item
                        else -> item.toString()
                    }
                    
                    progressCallback?.invoke(currentSuccess + currentFailed, total, itemName)
                    success
                }
            }
        }
        
        deferreds.awaitAll()
        
        ProcessResult(
            success = failedCount.get() == 0,
            successCount = successCount.get(),
            failedCount = failedCount.get(),
            total = total
        )
    }
    
    /**
     * 处理结果数据类
     */
    data class ProcessResult(
        val success: Boolean,
        val successCount: Int,
        val failedCount: Int,
        val total: Int
    )
}
