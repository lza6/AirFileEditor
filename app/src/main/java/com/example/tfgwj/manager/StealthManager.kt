package com.example.tfgwj.manager

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.tfgwj.utils.AppLogger
import java.io.File

/**
 * Phantom Stealth: 隐匿协议管理器
 * 职责：深度清理残留、销毁进程、从近期任务列表移除
 */
object StealthManager {
    private const val TAG = "StealthManager"

    /**
     * 执行深度隐匿指令
     */
    fun execute(context: Context) {
        AppLogger.action("StealthManager", "Phantom 隐匿协议已激活: 正在抹除残留并撤离...")

        // 1. 无情掐断正在排队的 Worker
        androidx.work.WorkManager.getInstance(context).cancelAllWork()

        // 2. 深度擦除物理缓存 (避开用户保存的核心数据库配置)
        try {
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()

            // 清理 tfgwj 专属 logs 目录
            val filesDir = context.getExternalFilesDir(null)
            val logsDir = File(filesDir?.parentFile?.parentFile?.parentFile?.parentFile, "Documents/tfgwj/logs")
            if (logsDir.exists()) logsDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Phantom 物理擦除部分拦截", e)
        }

        // 3. 准备自我销毁
        if (context is android.app.Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                context.finishAndRemoveTask()
            } else {
                context.finish()
            }
        }

        // 4. 彻底割裂进程
        Process.killProcess(Process.myPid())
        System.exit(0)
    }
}
