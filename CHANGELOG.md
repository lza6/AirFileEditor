# Changelog

## 16.2.0 - 2026-08-29

### 架构
- 新增 `TaskControllerProvider`（:core），统一 `FileReplaceWorkerV2`（写入）、`ConfigRepositoryImpl`（读取/取消）、3 个 CopyStrategy、ConfigRepositoryProvider 为同一 TaskController 单例，修复任务进度状态源断裂（P0）
- 删除孤儿死代码 `MainViewModel.kt`（无外部引用）

### 测试
- 新增 `TaskControllerProviderTest` 3 用例：单例一致性、状态机可推进、委托实现初始 IDLE
- 新增 `ExtractManagerSecurityTest` 6 用例 + `ArchiveManagerSecurityTest` 7 用例（解压安全红线）

### 工程
- 版本号升至 16.2.0 / versionCode=10
- 全量单测 `--rerun-tasks` 通过（:app/:core/:domain，224 @Test）
- Jacoco 覆盖率门禁真实执行（行覆盖 ≥27%，分支 ≥30%，棘轮基线）

## 16.1.1 - 2026-08-28

### 架构
- 授权按钮可用性：以 availableModes 代替 bestMode 判定（PermissionController.kt）
- 冷启动空包名不再崩溃：fail-closed 收敛为 NONE 结果（PermissionChecker.kt）
- 修复 Root 判定能力误报与命令协议兼容（RootChecker.kt）

## 16.1.0 - 2026-08-27

### 工程
- 凭据外置：keystore.properties + 环境变量，去除 build.gradle 明文密码
- 修复 Jacoco 覆盖率门禁真实执行

### 测试
- 补全解压安全链路测试

## 16.0.0 - 2026-08-26

### 架构
- 任务引擎收口：MainActivity 拆分委派控制器
- AppLogger 日志事件化：迁移至 SharedFlow

### 工程
- 删除 IoOptimizer/ReplaceProgressManager 残留
- Toast 清零：消除 UI 层残留 Toast
- versionCode=8

### 测试
- 补测试：覆盖任务引擎收口相关用例

## 15.0.0 - 2026-08-26

### 架构
- 新增 TaskController 接口 (domain 层) + TaskControllerImpl 实现 (core 层)
- ReplaceProgressManager 全局单例标记 @Deprecated，8 个文件迁移至 TaskController
- **UI 层 3 个文件 ReplaceProgressManager 引用全部迁移**：MainActivity / FloatingBallManager / MainViewModel
- 新增 IoEngine.acquireBuffer/releaseBuffer 缓冲区管理接口
- ExtractManager/UniversalExtractor 缓冲区管理迁移至 IoEngine.bufferManager

### UI
- 创建 SnackbarManager 统一管理器，替换 MainActivity 中 19 处 Toast.makeText()
- 修复 SnackbarHostWrapper 多参数签名 ktlint 格式违规

### 测试
- 新增 AdaptiveBufferManagerTest 10 个用例（缓冲区扩容/收缩/边界/并发）
- 新增 TransactionWalManagerTest 9 个用例（持久化/回滚/清理/边界）
- 新增 PathConstantsTest 20 个用例（包名校验/路径构建/穿越拒绝/映射）
- 新增 CopyConfigTest 5 个用例（默认值/修改/边界）
- 修复 ProgressTrackerTest StackOverflowError（mockkStatic 与 Dispatchers.Main 冲突）
- 修复 Windows Gradle 文件锁问题（移除 doFirst 清理逻辑）

### 工程
- 修复 .kotlin/ 编译器缓存文件误追踪，更新 .gitignore
- checkQuality 门禁通过（Detekt + Ktlint + JaCoCo）
- 全量回归测试通过（:core 54+ 用例 / :domain 测试 / :app 测试）

## 14.1.0 - 2026-08-24

### 测试
- 新增 IoEngineTest (10+ 用例覆盖三种策略路径)
### UI
- 骨架屏接入 MainDashboard 加载态
- 空态接入 PatchVersionCard 空列表
- 动画按钮接入 MainPackCard 关键操作
- 新增替换历史 UI (Screen+ViewModel+列表+详情)
### 工程
- checkQuality 门禁通过
- 清理误提交的本地 AI 配置
- 版本号升至 14.1.0 / versionCode 6

## 14.0.0 - 2026-08-24

### 引擎融合
- 创建 IoEngine 统一复制引擎，合并 IoOptimizer 与 HighPerformanceIoEngine 核心能力
- NormalCopyOrchestrator 迁移至 IoEngine 统一入口
### 架构
- 抽取 AbstractShellOrchestrator 基类，消除 Root/Shizuku Orchestrator 约 60% 重复代码
### UI
- 新增 Shape/Animation 系统、OKLCH 色彩空间
- 新增 SkeletonLoader/AnimatedButton/EmptyStateView 组件
- 按钮加入按压缩放动画反馈
### 工程
- 添加 android.suppressUnsupportedCompileSdk=36 抑制构建警告
- 版本号升至 14.0.0 / versionCode 5
### 测试
- 新增 IoEngineTest 全面覆盖三种策略路径
- 修复 TransactionWalManagerTest Robolectric 依赖

## 13.0.0 - 2026-08-23

### 安全

- 统一 `ArchiveEntryValidator` 路径穿越校验
- 新增 `ArchiveSafetyGuard`：重复条目、单文件 4GiB、总量 16GiB、staging 失败清理
- ZIP/7z 先完整预检再写入；TAR/TAR.GZ/TAR.XZ 重开流两遍
- RAR 与不支持格式在创建输出前失败
- Shizuku AIDL 命令/路径白名单 fail-closed

### 任务闭环

- `TaskPhase` 七态成为唯一权威源；终态不算 `isReplacing`
- `startReplace` 使用 `enqueueUniqueWork(KEEP)`
- `cancelReplace` 同时取消 WorkManager 并置 CANCELLED
- `dismissReplaceResult` 只复位，不取消任务
- 空/非法包名 fail-closed，无默认包名兜底

### 工程

- `:data` compileSdk 35 → 36
- 删除 Manifest 中不存在的 `FileReplaceService`
- 产品版本号对齐架构代次：`13.0.0` / versionCode 4
