package com.example.tfgwj.utils

import com.example.tfgwj.performance.AdaptiveBufferManager
import com.example.tfgwj.performance.AdaptiveBufferManagerImpl
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Semaphore

/**
 * 听风改文件高性能 I/O 核心引擎 (V14.0.0 性能引擎 2.0)
 *
 * 核心优化特性：
 * 1. 动态自适应滑动窗口缓冲区 (AdaptiveBufferManager)
 * 2. mmap 大文件零拷贝通道 (MmapZeroCopyEngine，限流保护)
 * 3. 小文件批处理聚合刷盘 (SmallFileBatchWriter)
 * 4. 三段抽样快速哈希预检 (TriSegmentSamplingHasher)
 */
object HighPerformanceIoEngine {

    val bufferManager: AdaptiveBufferManager = AdaptiveBufferManagerImpl()
    private val mmapLimiter = Semaphore(16)
    const val MMAP_THRESHOLD = 16L * 1024 * 1024 // 16MB
    const val SAMPLING_THRESHOLD = 1024 * 1024 // 1MB

    /**
     * 高性能智能复制入口：大文件走 mmap，常规文件走自适应缓冲管道
     */
    fun fastCopy(source: File, target: File): Long {
        if (!source.exists()) return 0L
        target.parentFile?.mkdirs()

        // 策略 1: 大文件 (>= 16MB) 尝试 mmap 零拷贝
        if (source.length() >= MMAP_THRESHOLD && mmapLimiter.tryAcquire()) {
            try {
                RandomAccessFile(source, "r").use { srcRaf ->
                    RandomAccessFile(target, "rw").use { dstRaf ->
                        val srcCh = srcRaf.channel
                        val dstCh = dstRaf.channel
                        val size = srcCh.size()
                        dstRaf.setLength(size)

                        val srcBuf = srcCh.map(FileChannel.MapMode.READ_ONLY, 0, size)
                        val dstBuf = dstCh.map(FileChannel.MapMode.READ_WRITE, 0, size)
                        dstBuf.put(srcBuf)
                        dstBuf.force()
                        return size
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("IoEngine", "mmap 复制失败，自动降级至自适应流: ${e.message}")
            } finally {
                mmapLimiter.release()
            }
        }

        // 策略 2: 自适应缓冲区流拷贝
        val buffer = bufferManager.acquireBuffer()
        var totalBytes = 0L
        val startNano = System.nanoTime()

        try {
            FileInputStream(source).use { fis ->
                FileOutputStream(target).use { fos ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        totalBytes += read
                    }
                    fos.flush()
                }
            }
        } finally {
            val durationNanos = System.nanoTime() - startNano
            bufferManager.recordIoDuration(totalBytes, durationNanos)
            bufferManager.releaseBuffer(buffer)
        }

        return totalBytes
    }

    /**
     * 三段抽样哈希：针对 >= 1MB 文件，提取头中尾各 64KB + 大小生成特征指纹
     */
    fun generateSamplingFingerprint(file: File): String {
        if (!file.exists()) return ""
        val length = file.length()
        if (length < SAMPLING_THRESHOLD) {
            return FileHasher.calculateMD5(file) ?: ""
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val segmentSize = 64 * 1024
                val buffer = ByteArray(segmentSize * 3)

                // 头部
                raf.seek(0)
                val headRead = raf.read(buffer, 0, segmentSize).coerceAtLeast(0)

                // 中部
                val midPos = (length / 2 - segmentSize / 2).coerceAtLeast(0)
                raf.seek(midPos)
                val midRead = raf.read(buffer, headRead, segmentSize).coerceAtLeast(0)

                // 尾部
                val tailPos = (length - segmentSize).coerceAtLeast(0)
                raf.seek(tailPos)
                val tailRead = raf.read(buffer, headRead + midRead, segmentSize).coerceAtLeast(0)

                val totalSampleBytes = headRead + midRead + tailRead
                val md5 = java.security.MessageDigest.getInstance("MD5")
                md5.update(buffer, 0, totalSampleBytes)
                md5.update(length.toString().toByteArray())
                md5.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            FileHasher.calculateMD5(file) ?: ""
        }
    }
}
