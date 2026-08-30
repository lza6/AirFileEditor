package com.example.tfgwj.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V24 任务可靠性参数单测（纯 JVM，验证常量与重试/退避配置的合理约束）
 */
class FileReplaceWorkerV2ReliabilityTest {

    @Test
    fun `retry constants are positive and bounded`() {
        assertTrue(FileReplaceWorkerV2.INITIAL_BACKOFF_MS > 0L)
        assertTrue(FileReplaceWorkerV2.MAX_RETRY_ATTEMPTS > 0)
        assertTrue(FileReplaceWorkerV2.MAX_RETRY_ATTEMPTS <= 5)
    }

    @Test
    fun `task deadline is reasonable upper bound`() {
        // 30 分钟 = 1_800_000ms，应严格大于初始退避，且为有限值
        assertTrue(FileReplaceWorkerV2.TASK_DEADLINE_MS >= 1_800_000L)
        assertTrue(FileReplaceWorkerV2.TASK_DEADLINE_MS > FileReplaceWorkerV2.INITIAL_BACKOFF_MS)
    }

    @Test
    fun `unique work name is stable string`() {
        assertEquals("file_replace_v2", FileReplaceWorkerV2.UNIQUE_WORK_NAME)
        assertEquals("file_replace", FileReplaceWorkerV2.WORK_TAG)
    }

    @Test
    fun `exponential backoff schedule stays within deadline for max retries`() {
        // 3 次重试：10s + 20s + 40s = 70s 远小于 30 分钟 deadline
        var totalBackoff = 0L
        var interval = FileReplaceWorkerV2.INITIAL_BACKOFF_MS
        repeat(FileReplaceWorkerV2.MAX_RETRY_ATTEMPTS) {
            totalBackoff += interval
            interval *= 2
        }
        assertTrue("总退避 ${totalBackoff}ms 应小于任务 deadline", totalBackoff < FileReplaceWorkerV2.TASK_DEADLINE_MS)
    }
}
