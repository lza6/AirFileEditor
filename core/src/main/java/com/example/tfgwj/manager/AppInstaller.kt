package com.example.tfgwj.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AppInstaller {
    private const val TAG = "AppInstaller"

    /**
     * V5.0.0 Omni-Installer 全能分发：环境智能嗅探静默安装
     * 具备 Root/Shizuku/Native 三重智能降级覆盖策略
     */
    suspend fun installApk(
        context: Context,
        apkFile: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!apkFile.exists() || !apkFile.canRead()) {
                Log.e(TAG, "APK 文件不存在或因沙盒系统权限导致不可读，静默分发失败。")
                return@withContext false
            }

            // Mode 1: Root 静默极速版
            if (RootChecker.isRooted()) {
                Log.i(TAG, "🔴 命中 Omni-Installer [Root Mode] 物理级强盖")
                val output = RootChecker.executeRootCommand("pm install -r \"${apkFile.absolutePath}\"")
                if (output?.contains("Success", ignoreCase = true) == true) {
                    Log.i(TAG, "Root 安装成功")
                    return@withContext true
                }
            }

            // Mode 2: Shizuku IPC 越权版
            val shizukuManager = ShizukuManager.getInstance(context)
            if (shizukuManager.isAuthorized.value) {
                Log.i(TAG, "🟢 命中 Omni-Installer [Shizuku Binder Mode] 进程极静默覆盖")
                val output = shizukuManager.executeCommandWithOutput("pm install -r \"${apkFile.absolutePath}\"")
                if (output?.contains("Success", ignoreCase = true) == true) {
                    Log.i(TAG, "Shizuku 安装成功")
                    return@withContext true
                }
            }

            // Mode 3: Native Fallback (普通意图触发安装，需用户确认)
            Log.i(TAG, "🟡 高权脱离掌控，降维启动 Omni-Installer [Native Fallback Mode] 发送标准广播")
            installViaNativeIntent(context, apkFile)
            return@withContext true
        }

    private suspend fun installViaNativeIntent(
        context: Context,
        apkFile: File,
    ) = withContext(Dispatchers.Main) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkUri: Uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile,
                    )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Native 无权限：意图发送折断，可能是由于 FileProvider 防护或沙盒隔离引起。", e)
        }
    }
}
