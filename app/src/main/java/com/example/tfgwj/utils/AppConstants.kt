package com.example.tfgwj.utils

object AppConstants {
    // 应用名称
    const val APP_NAME = "听风改文件"
    
    // 缓存目录名称
    const val CACHE_DIR_NAME = "听风改文件"
    
    // 解压目录名称
    const val EXTRACT_DIR_NAME = "extracted"
    
    // 和平精英默认包名
    const val DEFAULT_GAME_PACKAGE_NAME = "com.tencent.tmgp.pubgmhd"
    
    // 备用包名
    val ALTERNATIVE_GAME_PACKAGE_NAMES = arrayOf(
        "com.tencent.tmgp.pubgmhd",
        "com.pubg.krmobile",
        "com.rekoo.pubgm",
        "com.tencent.ig"
    )

    // 支持的压缩包格式
    val SUPPORTED_ARCHIVE_FORMATS = listOf(
        "zip", "jar", "gz", "gzip", "7z", "rar"
    )
    
    // 下载目录路径
    const val DOWNLOAD_DIR = "Download"
    
    // 123云盘目录路径
    const val YUNPAN_DIR = "123云盘"
    
    // 需要扫描的目录
    val SCAN_DIRECTORIES = arrayOf(DOWNLOAD_DIR, YUNPAN_DIR)
    
    // Android文件夹名称
    const val ANDROID_FOLDER_NAME = "Android"
    
    // 替换超时时间（毫秒）
    const val REPLACE_TIMEOUT = 60000L
    
    // 文件操作缓冲区大小
    const val BUFFER_SIZE = 8192
    
    // 通知渠道ID
    const val NOTIFICATION_CHANNEL_ID = "file_replace_channel"
    
    // 通知ID
    const val NOTIFICATION_ID = 1001
    
    // 文件替换结果广播
    const val ACTION_REPLACE_RESULT = "com.example.tfgwj.action.REPLACE_RESULT"
    
    // 结果Extras
    const val EXTRA_SUCCESS_COUNT = "success_count"
    const val EXTRA_FAILED_COUNT = "failed_count"
    const val EXTRA_TOTAL_COUNT = "total_count"
    const val EXTRA_ERROR_MESSAGE = "error_message"
    
    // Shizuku权限请求码
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 10001
    
    // 存储权限请求码
    const val STORAGE_PERMISSION_REQUEST_CODE = 10002
}