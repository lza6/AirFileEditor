# Changelog

## 15.0.0 - 2026-08-26

### 架构
- 新增 TaskController 接口 (domain 层) + TaskControllerImpl 实现 (core 层)
- ReplaceProgressManager 全局单例标记 @Deprecated，8 个文件迁移至 TaskController
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
