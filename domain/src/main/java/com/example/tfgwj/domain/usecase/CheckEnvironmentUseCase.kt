package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.model.PermissionStatus
import com.example.tfgwj.domain.repository.ConfigRepository

/**
 * 业务用例：环境与权限验证
 */
class CheckEnvironmentUseCase(
    private val repository: ConfigRepository
) {
    suspend operator fun invoke(packageName: String, forceRefresh: Boolean = false): PermissionStatus {
        return repository.checkEnvironment(packageName, forceRefresh)
    }
}
