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

/**
 * AppInstaller — 全能安装器
 *
 * Android 版本兼容性说明：
 * - Android 10- (API 29-): pm install 在 shell 权限下可用，Shizuku / Root 均可成功。
 * - Android 11+ (API 30+): pm install 需 INSTALL_PACKAGES 权限，Shizuku 的 shell 权限在
 *   部分设备上会因权限收紧而失败（需 `pm install -r --user 0` 绕过）。
 * - Android 12+ (API 31+): 更严格，Shizuku 需 `pm install -r --user 0 --pkg` 参数。
 * - Android 14+ (API 34+): 新增 `--dont-kill` 限制，前台服务安装受限。
 * - HarmonyOS 1.x-4.x: 兼容层保留 pm install 能力，静默安装行为与 EMUI 11+ 一致。
 * - HarmonyOS NEXT: 纯血鸿蒙不可运行 Android APK，本模块不适用。
 *
 * 安装策略：Root → Shizuku IPC → Native Intent（系统安装器，需用户确认）
 */
object AppInstaller {
    private const val TAG = "AppInstaller"

    /**
     * 安装结果契约（V13：不再用布尔值吞掉失败原因）
     */
    sealed class InstallResult {
        data class Success(val mode: String) : InstallResult()
        data class Failure(val mode: String, val reason: String) : InstallResult()
    }

    /**
     * V5.0.0 Omni-Installer 全能分发：环境智能嗅探静默安装
     * 具备 Root/Shizuku/Native 三重智能降级覆盖策略
     *
     * V13 收口：返回明确的 InstallResult，安装失败必须向上返回。
     */
    suspend fun installApk(
        context: Context,
        apkFile: File,
    ): InstallResult =
        withContext(Dispatchers.IO) {
            if (!apkFile.exists() || !apkFile.canRead()) {
                Log.e(TAG, "APK 文件不存在或因沙盒系统权限导致不可读，静默分发失败。")
                return@withContext InstallResult.Failure("NONE", "APK 不存在或不可读")
            }

            // Mode 1: Root 静默极速版
            if (RootChecker.isRooted()) {
                Log.i(TAG, "🔴 命中 Omni-Installer [Root Mode] 物理级强盖")
                val output = RootChecker.executeRootCommand("pm install -r \"${apkFile.absolutePath}\"")
                if (output?.contains("Success", ignoreCase = true) == true) {
                    Log.i(TAG, "Root 安装成功")
                    return@withContext InstallResult.Success("ROOT")
                }
                Log.w(TAG, "Root 安装失败，输出: ${output?.take(200)}")
            }

            // Mode 2: Shizuku IPC 越权版
            val shizukuManager = ShizukuManager.getInstance(context)
            if (shizukuManager.isAuthorized.value) {
                Log.i(TAG, "🟢 命中 Omni-Installer [Shizuku Binder Mode] 进程极静默覆盖")
                val output = shizukuManager.executeCommandWithOutput("pm install -r \"${apkFile.absolutePath}\"")
                if (output?.contains("Success", ignoreCase = true) == true) {
                    Log.i(TAG, "Shizuku 安装成功")
                    return@withContext InstallResult.Success("SHIZUKU")
                }
                Log.w(TAG, "Shizuku 安装失败，输出: ${output?.take(200)}")
            }

            // Mode 3: Native Fallback (普通意图触发安装，需用户确认)
            Log.i(TAG, "🟡 高权脱离掌控，降维启动 Omni-Installer [Native Fallback Mode] 发送标准广播")
            val launched = installViaNativeIntent(context, apkFile)
            if (launched) {
                // 系统安装器已拉起，但真实安装结果由系统回调决定，这里只能保证"已发起"
                InstallResult.Success("NATIVE_INTENT")
            } else {
                InstallResult.Failure("NATIVE_INTENT", "无法拉起系统安装器")
            }
        }

    private suspend fun installViaNativeIntent(
        context: Context,
        apkFile: File,
    ): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Native 无权限：意图发送折断，可能是由于 FileProvider 防护或沙盒隔离引起。", e)
            false
        }
    }
}
