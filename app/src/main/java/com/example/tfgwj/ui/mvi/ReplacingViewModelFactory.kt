package com.example.tfgwj.ui.mvi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.tfgwj.data.repository.ConfigRepositoryImpl
import com.example.tfgwj.domain.repository.ConfigRepository
import com.example.tfgwj.domain.usecase.*
import com.example.tfgwj.manager.MainPackManager
import com.example.tfgwj.manager.PatchManager
import com.example.tfgwj.shizuku.ShizukuManager

/**
 * 增强版 ViewModel 工厂 (V12 架构驱动)
 */
class ReplacingViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReplacingViewModel::class.java)) {
            val shizukuManager = ShizukuManager.getInstance(context)
            val patchManager = PatchManager.getInstance()
            val mainPackManager = MainPackManager.getInstance()

            val repository: ConfigRepository =
                ConfigRepositoryImpl(
                    context.applicationContext,
                    shizukuManager,
                    patchManager,
                    mainPackManager,
                )

            val replaceFileUseCase = ReplaceFileUseCase(repository)
            val checkEnvironmentUseCase = CheckEnvironmentUseCase(repository)
            val managePatchUseCase = ManagePatchUseCase(repository)
            val manageFileTimeUseCase = ManageFileTimeUseCase(repository)

            @Suppress("UNCHECKED_CAST")
            return ReplacingViewModel(
                replaceFileUseCase,
                checkEnvironmentUseCase,
                managePatchUseCase,
                manageFileTimeUseCase,
                repository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
