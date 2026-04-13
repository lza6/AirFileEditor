package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ConfigRepository

/**
 * 业务用例：管理文件时间
 */
class ManageFileTimeUseCase(
    private val repository: ConfigRepository
) {
    suspend fun randomize(path: String): Result<Pair<Int, Long>> = repository.randomizeTime(path)

    suspend fun applyTime(path: String, timestamp: Long): Result<Int> = repository.setCustomTime(path, timestamp)

    fun getCurrentTime(path: String): Long? = repository.getFileTime(path)
}
