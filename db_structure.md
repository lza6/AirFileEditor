# Database Structure: tfgwj (Jetpack DataStore)

> V19 更新：对齐真实 `PreferencesManager` 键；删除已废弃的 `IoOptimizer` 描述；补充 `replace_history` 历史存储说明。

## 1. 偏好设置 (Preferences - DataStore `tfgwj_preferences`)
采用 Jetpack DataStore 进行高性能、非阻塞式的配置存储。

| 键名 (Key) | 类型 (Type) | 默认值 (Default) | 说明 (Description) |
|---|---|---|---|
| `app_package_name` | String | "" | 当前操作的目标应用包名（**唯一来源，禁止默认包名回退**） |
| `last_main_pack_path` | String | "" | 上次选择的主包完整路径 |
| `last_selected_folder_path` | String | "" | 上次在文件选择器中选中的路径 |
| `shizuku_authorized` | Boolean | false | Shizuku 是否已授权 |
| `auto_stop_app` | Boolean | true | 替换前是否自动停止目标应用 |
| `use_auto_password` | Boolean | false | 解压是否使用自动密码 |
| `last_decrypt_password` | String | "" | 上次解压密码 |
| `locked_time_enabled` | Boolean | false | 是否启用了时间锁定功能 |
| `locked_time` | Long | 0 | 锁定的时间戳 (毫秒) |

> 说明：`auto_clean_cache` / `last_selected_mode` / `best_mode_detected` / `shizuku_available` / `root_available` / `native_available` 键已在旧版 PreferencesManager 中移除；模式可用性由 `PermissionManager` 持久化到 `听风改文件/.config/env_status.json`（JSON，含设备指纹校验）。

## 2. 替换历史 (Replace History - DataStore `replace_history`)
`ReplaceHistoryManager` 以 JSON 数组持久化最近 **50 条**替换记录（`ReplaceHistoryItem`）。

### 写入路径（V19 审计闭环）
1. `ConfigRepositoryImpl.startReplace` enqueue `FileReplaceWorkerV2` 唯一任务；
2. 独立 `auditScope`（SupervisorJob + Dispatchers.IO）观察该 Work 的 `getWorkInfoByIdFlow` 终态（SUCCEEDED / FAILED / CANCELLED）；
3. 终态到达后从 Worker `outputData` 读取 `processed` / `total` / `verified` / `backup_path` / `error_message`，映射为 `ReplaceHistoryItem` 写入；
4. 进程被杀导致协程死亡时静默放弃（不阻塞替换本身）。

### 记录字段
| 字段 | 类型 | 说明 |
|---|---|---|
| `timestamp` | Long | 写入时刻（毫秒） |
| `packageName` | String | 目标应用包名 |
| `sourcePath` | String | 源主包路径 |
| `targetPath` | String | `Android/data/<pkg>` |
| `totalFiles` | Int | Worker 上报总数 |
| `successCount` | Int | 成功/已处理数 |
| `failedCount` | Int | 失败数（非成功终态恒 1） |
| `errors` | List\<String\> | 错误详情 |
| `backupPath` | String? | 任务前备份路径 |

### 读取与操作
- `HistoryViewModel` 订阅 `history` Flow → `HistoryScreen`（列表/展开详情/删除单条/清空，均带确认对话框）。

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

## 4. IO 引擎 (IoEngine — 取代旧 IoOptimizer)

旧 `IoOptimizer`（动态缓冲区/并发控制/fastCopy/needsUpdate/parallelProcess）已于 V14-V17 统一收敛到 `core/performance/IoEngine`（分块 mmap、MemoryPressureGuard 内存水位、SmallFileBatchWriter 组件）。缓冲区策略不再按内存阶梯硬编码，改由 `AdaptiveBufferManager`（16KB~8MB 动态调优 + 内存水位 clamp）驱动。

### 并发控制
- **mmap 限流**: Semaphore(16)；超过则降级自适应流
- **高内存压力**: `MemoryPressureGuard` 判定 HIGH 时跳过 mmap、收缩缓冲
- **进度回调**: 支持实时进度更新