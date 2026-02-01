package com.example.tfgwj

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限请求 Contract
 */
class ShizukuRequestContract : ActivityResultContract<Unit, Boolean>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        return try {
            Shizuku.requestPermission(0)
            Intent() // 返回空 Intent，Shizuku 通过回调处理结果
        } catch (e: Exception) {
            Intent()
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
        return resultCode == android.app.Activity.RESULT_OK
    }
}
