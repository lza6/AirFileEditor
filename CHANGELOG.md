# Changelog

## 19.0.2 - 2026-08-30

### 审计
- `IoEngine.generateSamplingFingerprint` 核实为能力预留组件（三段抽样指纹，有实现+单测但无生产调用方）。当前增量路径已由 `FileHasher.areFilesEqualWithSampling`（三块字节比对）承担同等职责，不强行双写。已按 V17 对 SmallFileBatchWriter 的同款判例在文档中标注为能力预留，避免"有实现无调用点"的伪闭环表述。

## 19.0.1 - 2026-08-30

### 修复
- 审计成功计数：优先取 Worker 输出 `KEY_VERIFIED_FILES`（验证通过后的真实数），缺失回退 `KEY_PROCESSED`；避免增量跳过文件时历史 `successCount` 低于实际已验证数

## 19.0.0 - 2026-08-30

### 架构
- V19 数据持久化闭环 → **审计闭环**：`ConfigRepositoryImpl.startReplace` 不再在 Worker 线程内联写历史，而是 enqueue 后由独立 `auditScope`（SupervisorJob + Dispatchers.IO）观察 WorkManager 任务终态，成功/失败/取消均写入替换历史（读取 Worker outputData 的 processed/total/verified/backup/error）
  - 根治 V17 审查暴露的"Worker 注释掉的写历史 + 仓储内联写历史"双份逻辑与状态源歧义
  - 协程死亡（进程被杀）时静默放弃，不阻塞替换主流程；`buildHistoryItem` 为纯函数（可 JVM 单测）

### 测试
- `ConfigRepositoryContractTest` 增补 V19 审计映射 3 用例（成功映射/失败携带错误/无错误兜底）
- `:data` 模块测试从 5 → 8，仍经 CI 流水线（`:data:testDebugUnitTest`）

### 工程
- 版本号升至 19.0.0 / versionCode=12
- 全量单测 + checkQuality 全绿

## 17.0.1 - 2026-08-29

### 修复（独立 Critic 审查 V17.0.0 后闭环）
- **Blocking**: `IoEngine.channelCopy` 读侧 `read>0` 排除理论 0 返回防自旋；写侧 drain 循环防部分写入截断；失败时删除半成品目标文件并返回 0（与 fastCopy 契约一致）
- **Blocking**: `isSafeTargetPathForPackage` 单包边界校验**接线到真实路径**：RootCopyOrchestrator / ShizukuCopyOrchestrator / VerificationManager 三处从笼统 `data|obb` 前缀收窄为 `<targetPackage>/` 内（symlink 逃逸纵深真正生效）
- **Required**: `MemoryPressureGuard` **接线到 I/O**：`IoEngine.fastCopy` 高压力跳过 mmap、`FileReplaceWorkerV2` 任务前 `refreshMemoryPressure` 联动缓冲 clamp；删除 `VerificationManager` 冗余旧 `isSafeTargetPath`
- **Required**: `:data` 测试纳入 CI（`.github/workflows/ci.yml` 增加 `:data:testDebugUnitTest`）
- **Required**: `SmallFileBatchWriter` 明确为能力预留组件（Native 复制已有等效分桶），CHANGELOG 措辞改为真实状态
- **Verify**: 新增 `IoEngineTest` 内存水位接线测试（null 降级 LOW + 缓冲边界）

## 17.0.0 - 2026-08-29

### 架构
- V17 屎山清理：删除双引擎 `HighPerformanceIoEngine`（能力并入 `IoEngine`），收敛重复代码
- 删除硬编码包名 `AppConstants.DEFAULT_GAME_PACKAGE_NAME` / `ALTERNATIVE_GAME_PACKAGE_NAMES`
- 删除无消费方死 Intent（`UpdateMode`/`RefreshEnvironment`/`RequestStoragePermission`/`RequestShizukuPermission`/`SelectMainPack`/`ScanMainPacks`/`LaunchGame`/`CleanEnvironment`/`ApplyLockedTime`/`ScanArchives`/`ExtractAndUpdate`/`CopyLogs`/`CheckForUpdates`/`InstallUpdate`），MVI 契约最小化
- 删除 `FileReplaceWorkerV2` 注释死代码 + `updateProgressState` 无用方法
- 删除 `:core` 层 `android.widget.Toast` import（净化方向）
- 密码学默认包名回溯语义保留（`:core` 探测语义，非任务回退）

### 性能引擎 2.0 (V18)
- `IoEngine.mmapCopy` 改为**分块 mmap**：`MMAP_CHUNK_SIZE` 64MB/块 + `MMAP_MAX_FILE_SIZE` 2GB 超限走 `channelCopy` 流式写，防大文件 OOM
- 新增 `MemoryPressureGuard`：内存水位评估 LOW/MEDIUM/HIGH，动态降并发/禁用 mmap
- `MemoryPressureGuard` **已接线**：`IoEngine.fastCopy` 高压力下跳过 mmap 走自适应流；`IoEngine.refreshMemoryPressure` 由 `FileReplaceWorkerV2` 任务前调用并联动 `AdaptiveBufferManager.setBufferSize` clamp
- `SmallFileBatchWriter`：小文件攒批刷盘组件（能力预留，当前 Native 复制已用 `partition(<1KB) + batchFiles` 等效分桶；独立组件保留供后续接入）
- `AdaptiveBufferManager.setBufferSize` 显式 clamp 到 [min,max]，与内存水位联动

### 安全纵深 (V20)
- `ArchiveSafetyGuard.validateBomb`：压缩炸弹检测（条目数>10万拒绝；解压>512MB且压缩率>100x 拒绝）；接入 ExtractManager / UniversalExtractor 全部解压路径
- `isSafeTargetPathForPackage`（单包边界 + symlink 逃逸防护）**已接线到真实复制/验证路径**：RootCopyOrchestrator / ShizukuCopyOrchestrator / VerificationManager 三处校验从笼统 `data|obb` 前缀收窄为 `<targetPackage>/` 内

### 测试
- `:data` 模块补足测试（此前 0 测试）：`ConfigRepositoryContractTest` 5 用例
- 新增 `MemoryPressureGuardTest`(7) / `SmallFileBatchWriterTest`(6) / `ArchiveSafetyGuardBombTest`(6) / `IsSafeTargetPathTest`(3)
- `TaskControllerImplTest` 补终态交叉用例(2) / `AdaptiveBufferManagerTest` 补 clamp 用例(4) / `IoEngineTest` 补 mmap 常量用例(3)
- 全量单测 `@Test` 数：259

### 工程
- 版本号升至 17.0.0 / versionCode=11
- `checkQuality`（Detekt+Ktlint+JaCoCo）全绿通过
- E2E 模拟器（Android 15）三态验证通过：NATIVE 受限 / SHIZUKU 潜在方案未连接 / bestMode=NONE fail-closed 不崩溃

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
