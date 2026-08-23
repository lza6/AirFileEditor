package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ConfigRepository

/**
 * 业务用例：执行文件替换
 * 保持薄 UseCase：只做入参校验与调用，不做编排。
 */
class ReplaceFileUseCase(
    private val repository: ConfigRepository
) {
    private val packageNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    suspend operator fun invoke(sourcePath: String, targetPackage: String, incremental: Boolean = false): Result<String> {
        if (sourcePath.isBlank()) {
            return Result.failure(IllegalArgumentException("源路径不能为空"))
        }
        if (targetPackage.isBlank()) {
            return Result.failure(IllegalArgumentException("目标包名不能为空"))
        }
        if (!packageNamePattern.matches(targetPackage)) {
            return Result.failure(IllegalArgumentException("非法目标包名: $targetPackage"))
        }
        return repository.startReplace(sourcePath, targetPackage, incremental)
    }
}
