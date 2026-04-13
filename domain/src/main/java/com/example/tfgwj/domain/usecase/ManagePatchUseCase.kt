package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ArchiveInfo
import com.example.tfgwj.domain.repository.ConfigRepository
import com.example.tfgwj.domain.repository.PatchVersion

/**
 * 业务用例：管理小包（扫描、解压、提取）
 */
class ManagePatchUseCase(
    private val repository: ConfigRepository
) {
    suspend fun scanPatches(): List<PatchVersion> = repository.scanPatchVersions()

    suspend fun scanArchives(): List<ArchiveInfo> = repository.scanArchives()

    suspend fun extractAndInstall(archive: ArchiveInfo, password: String? = null): Result<String> {
        val versionName = archive.name.substringBeforeLast(".")
        return repository.extractArchive(archive.path, password, versionName)
    }
}
