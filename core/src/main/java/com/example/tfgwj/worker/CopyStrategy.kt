package com.example.tfgwj.worker

import android.content.Context
import androidx.work.Data
import androidx.work.workDataOf
import com.example.tfgwj.domain.model.TaskPhase
import java.io.File

/**
 * CopyStrategy - V7.0.0 Architecture Evolution
 *
 * Strategy interface for file copy operations.
 * Supports three modes: ROOT, SHIZUKU, and NATIVE.
 *
 * Each strategy implements the same copy workflow:
 * 1. Scan source files
 * 2. Prepare target environment
 * 3. Execute copy with progress tracking
 * 4. Verify results
 */
abstract class CopyStrategy(
    protected val context: Context,
    protected val targetPackage: String,
) {
    /**
     * Progress callback interface
     */
    interface ProgressCallback {
        fun onProgress(
            progress: Int,
            processed: Int,
            total: Int,
            currentFile: String,
            speed: Float = 0f,
            phase: TaskPhase = TaskPhase.REPLACING,
        )
    }

    /**
     * Execute batch copy operation
     * @param sourceDir Source directory containing Android/data or Android/obb
     * @param incrementalUpdate Whether to perform incremental update
     * @param callback Progress callback
     * @return CopyResult indicating success or failure
     */
    abstract suspend fun executeBatchCopy(
        sourceDir: File,
        incrementalUpdate: Boolean,
        callback: ProgressCallback?,
    ): CopyResult

    /**
     * Get strategy name for logging
     */
    abstract val strategyName: String

    /**
     * Copy result data class
     */
    data class CopyResult(
        val isSuccess: Boolean,
        val processedCount: Int = 0,
        val totalCount: Int = 0,
        val verifiedCount: Int = 0,
        val errorMessage: String? = null,
    ) {
        fun toWorkData(): Data =
            workDataOf(
                FileReplaceWorkerV2.KEY_PROCESSED to processedCount,
                FileReplaceWorkerV2.KEY_TOTAL to totalCount,
                FileReplaceWorkerV2.KEY_VERIFIED_FILES to verifiedCount.toString(),
                FileReplaceWorkerV2.KEY_MODE to "COPY_STRATEGY",
                FileReplaceWorkerV2.KEY_ERROR_MESSAGE to (errorMessage ?: ""),
            )

        companion object {
            fun success(
                processedCount: Int,
                totalCount: Int,
                verifiedCount: Int = 0,
                mode: String,
            ): CopyResult =
                CopyResult(
                    isSuccess = true,
                    processedCount = processedCount,
                    totalCount = totalCount,
                    verifiedCount = verifiedCount,
                )

            fun failure(errorMessage: String): CopyResult =
                CopyResult(
                    isSuccess = false,
                    errorMessage = errorMessage,
                )
        }
    }

    /**
     * Get target base path based on package
     */
    protected fun getTargetBasePath(): String = "/storage/emulated/0/Android/data/$targetPackage"

    /**
     * Count files in directory using optimized method
     */
    protected fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }
}
