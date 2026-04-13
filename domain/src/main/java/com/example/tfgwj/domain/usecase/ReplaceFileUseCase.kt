package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ConfigRepository

/**
 * 业务用例：执行文件替换
 */
class ReplaceFileUseCase(
    private val repository: ConfigRepository
) {
    suspend operator fun invoke(sourcePath: String, targetPackage: String, incremental: Boolean = false): Result<String> {
        if (sourcePath.isEmpty() || targetPackage.isEmpty()) {
            return Result.failure(IllegalArgumentException("路径或包名不能为空"))
        }
        return repository.startReplace(sourcePath, targetPackage, incremental)
    }
}
