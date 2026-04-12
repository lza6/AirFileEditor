package com.example.tfgwj.ui.navigation

/**
 * 导航路由定义
 *
 * 集中管理所有屏幕路由，避免硬编码字符串
 *
 * @version V11.0.0 - Navigation Compose
 */
sealed class Screen(val route: String) {
    object Main : Screen("main")

    object Help : Screen("help")

    object Settings : Screen("settings")

    object PerformanceDashboard : Screen("performance_dashboard")

    object ArchiveList : Screen("archive_list")

    object ModeSelection : Screen("mode_selection")

    /**
     * 带参数的路由
     */
    object ArchiveDetail : Screen("archive_detail/{archivePath}") {
        fun createRoute(archivePath: String): String {
            return "archive_detail/$archivePath"
        }
    }
}

/**
 * 导航参数定义
 */
object NavArgs {
    const val ARCHIVE_PATH = "archivePath"
}
