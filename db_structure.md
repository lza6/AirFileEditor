# Database Structure: tfgwj (Jetpack DataStore)

## 1. 偏好设置 (Preferences - DataStore)
采用 Jetpack DataStore 进行高性能、非阻塞式的配置存储。

| 键名 (Key) | 类型 (Type) | 默认值 (Default) | 说明 (Description) |
|---|---|---|---|
| `app_package_name` | String | (预设列表首位) | 当前操作的目标应用包名 |
| `last_main_pack_path` | String | "" | 上次选择的主包完整路径 |
| `last_selected_folder_path` | String | "" | 上次在文件选择器中选中的路径 |
| `locked_time_enabled` | Boolean | false | 是否启用了时间锁定功能 |
| `locked_time` | Long | 0 | 锁定的时间戳 (毫秒) |
| `auto_clean_cache` | Boolean | true | 替换前是否自动执行智能缓存优化 |
| `last_selected_mode` | String | "AUTO" | 上次选择的访问模式 (AUTO/ROOT/NATIVE/SHIZUKU) |
| `best_mode_detected` | String | "" | 系统检测到的最佳模式 |
| `shizuku_available` | Boolean | false | Shizuku 是否可用 |
| `root_available` | Boolean | false | Root 权限是否可用 |
| `native_available` | Boolean | false | 原生模式是否可用 |

## 2. 替换历史 (Replace History - 内存/日志)
当前版本通过 `ReplaceHistoryManager` 在内存中维护最近的替换结果，并持久化到 `AppLogger` 文件中。

### 日志结构 (Structured Logging)
- **路径**: `/storage/emulated/0/Documents/tfgwj/logs/` (如果有权限)
- **格式**: `[timestamp] [level] [tag] message`
- **关键操作追踪**: 记录每次替换的 `WorkID`, `Mode`, `ProcessedCount`, `SuccessRate`.

## 3. Omni-Mode 智能检测系统 (v3.1.0 新增)

### 权限状态数据结构
```kotlin
data class PermissionStatus(
    val hasRoot: Boolean,                    // 是否有 Root 权限
    val hasManageStorage: Boolean,           // 是否有管理存储权限
    val isShizukuAvailable: Boolean,         // Shizuku 是否可用
    val hasShizukuPermission: Boolean,       // 是否已授权 Shizuku
    val canAccessPrivateDir: Boolean,        // 是否能访问私有目录
    val availableModes: List<AccessMode>,    // 可用的访问模式列表
    val bestMode: AccessMode,                // 最佳访问模式
    val lastSelectedMode: AccessMode,        // 上次选择的模式
    val isShizukuAuthorized: Boolean,        // Shizuku 是否已授权
    val isShizukuConnected: Boolean          // Shizuku 服务是否已连接
)

enum class AccessMode {
    NONE,       // 无可用模式
    ROOT,       // Root 模式
    NATIVE,     // 原生模式
    SHIZUKU     // Shizuku 模式
}
```

### 检测流程
1. **Root 检测**: 通过 `RootChecker.isRooted()` 检测设备是否已 Root
2. **Shizuku 检测**: 检测 Shizuku 应用是否安装并运行
3. **Native 检测**: 检测是否具有标准存储访问权限
4. **物理验证**: 对每种模式进行实际的文件读写测试
5. **最佳模式选择**: 根据检测结果的优先级选择最佳模式

## 4. IoOptimizer 性能优化 (v3.1.0 新增)

### 动态缓冲区管理
```kotlin
object IoOptimizer {
    // 根据设备内存动态计算缓冲区大小
    fun getOptimalBufferSize(): Int

    // 获取缓冲区（使用对象池复用）
    fun acquireBuffer(): ByteArray

    // 释放缓冲区
    fun releaseBuffer(buffer: ByteArray)

    // 快速文件复制
    fun fastCopy(source: File, target: File): Boolean

    // 检查文件是否需要更新
    fun needsUpdate(source: File, target: File): Boolean

    // 并发处理文件列表
    fun parallelProcess(
        items: List<T>,
        action: (T) -> Boolean,
        progressCallback: ((Int, Int, String) -> Unit)? = null
    ): ProcessResult
}
```

### 缓冲区大小策略
| 设备内存 | 缓冲区大小 | 说明 |
|---|---:|---|
| < 128MB | 512 KB | 低端设备，避免 OOM |
| 128-256MB | 1 MB | 中低配设备 |
| 256-512MB | 2 MB | 中配设备 |
| 512MB-1GB | 3 MB | 高配设备 |
| >= 1GB | 4 MB | 旗舰设备 |

### 并发控制
- **最大并发数**: 16 (通过 Semaphore 控制)
- **动态调整**: 根据可用 CPU 核心数自动调整
- **进度回调**: 支持实时进度更新