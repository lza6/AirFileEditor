# V9.0.0 质量保证基石 - 详细任务清单 ✅ 已完成

## 🎯 阶段目标
建立完整的测试基础设施，核心模块单元测试覆盖率 > 80%

---

## 📋 任务分解

### Task 9.1: 测试基础设施搭建 ✅
- [x] 在 app/build.gradle.kts 中添加测试依赖
  - kotlin-test (JUnit 4)
  - mockk (Mocking)
  - turbine (Flow 测试)
  - androidx.test:core (Robolectric)
- [x] 配置 Jacoco 代码覆盖率
  - 添加 jacoco-gradle-plugin
  - 配置 coverage 任务
  - 生成 HTML 报告
- [x] 创建测试配置文件
  - src/test/resources/ 目录
  - test-data/ 测试资源文件
- [x] 配置 GitHub Actions CI
  - .github/workflows/ci.yml
  - 自动运行测试
  - 上传覆盖率到 Codecov

### Task 9.2: 测试数据工厂
- [ ] 创建 TestDataFactory.kt
  - 生成测试用 File 对象（不同大小）
  - 生成测试用 Directory 结构
  - 生成测试用 CopyTask 数据
  - 生成测试用 OrchestratorResult
- [ ] 创建 TestFixtures
  - 临时文件夹管理（使用 TemporaryFolder）
  - 测试文件清理（@After 清理）

### Task 9.3: ProgressTracker 单元测试
- [ ] 创建 ProgressTrackerTest.kt
- [ ] 测试初始化逻辑
  - initialize(totalFiles) 正确设置状态
  - startTime 被正确记录
- [ ] 测试进度更新
  - updateProgress 正确计算百分比
  - 速度计算准确性
- [ ] 测试双级节流
  - WM 节流：1000ms 间隔
  - UI 节流：32ms 间隔 (~30FPS)
  - initialProgressThreshold 前 5 个文件高频更新
- [ ] 测试阶段切换
  - REPLACING → VERIFYING 阶段
  - 进度计算不受影响
- [ ] 测试边界条件
  - totalFiles = 0
  - processed > totalFiles
  - 并发调用 updateProgress

### Task 9.4: SpeedCalculator 单元测试
- [ ] 创建 SpeedCalculatorTest.kt
- [ ] 测试窗口机制
  - 3 秒滑动窗口
  - 过期样本自动清理
- [ ] 测试速度计算
  - 准确计算文件/秒
  - 瞬时速度 fallback
  - 时间差为 0 的处理
- [ ] 测试重置功能
  - reset() 清空所有状态

### Task 9.5: FileStatistics 单元测试
- [ ] 创建 FileStatisticsTest.kt
- [ ] 测试文件统计
  - countFiles() 准确统计
  - Root/Shizuku/Native 降级逻辑
- [ ] 测试文件序列
  - getFileSequence() 惰性求值
  - 过滤非文件项
- [ ] 测试批次生成
  - batchFiles() 正确分块
  - 默认批次大小 500

### Task 9.6: FileHasher 单元测试
- [ ] 创建 FileHasherTest.kt
- [ ] 测试 MD5 计算
  - 已知哈希值文件验证
  - 空文件处理
  - 大文件（100MB+）性能
- [ ] 测试抽样哈希
  - areFilesEqualWithSampling
  - 首/中/尾 3 个区块比对
  - 小文件（< 15MB）退化到全量
- [ ] 测试边界情况
  - 文件不存在
  - 文件大小不同
  - 权限不足

### Task 9.7: IoOptimizer 单元测试
- [ ] 创建 IoOptimizerTest.kt
- [ ] 测试缓冲区管理
  - acquireBuffer() 从池获取或新建
  - releaseBuffer() 归还池中
  - 池大小限制 8
- [ ] 测试 fastCopy
  - mmap 路径正确性
  - NIO fallback 机制
  - 分片映射（8MB chunks）
  - 目标目录自动创建
- [ ] 测试 needsUpdate
  - 目标不存在 → true
  - 大小不同 → true
  - 时间相同 → false（快速路径）
  - 小文件全量 MD5
  - 大文件抽样哈希
- [ ] 测试并发控制
  - parallelProcess() 并发度限制
  - Semaphore 正确工作
  - 进度回调触发

### Task 9.8: CopyConfig 单元测试
- [ ] 创建 CopyConfigTest.kt
- [ ] 测试默认配置
  - getDefault() 根据设备能力动态计算
  - CPU 核心数 × 2（范围 4-32）
  - Root/Shizuku 保守并发（2）
  - Native 高并发（baseConcurrent）
- [ ] 测试配置参数
  - WM 间隔 1000ms
  - UI 间隔 32ms
  - 初始阈值 5
  - 批次大小 32/500

### Task 9.9: PathConstants 单元测试
- [ ] 创建 PathConstantsTest.kt
- [ ] 测试路径构建
  - buildTargetDataPath() → /storage/emulated/0/Android/data/{pkg}
  - buildTargetObbPath() → /storage/emulated/0/Android/obb/{pkg}
  - buildTargetFilePath() 正确拼接
- [ ] 测试路径解析
  - extractAndroidType() 区分 data/obb
  - calculateRelativePath() 正确计算相对路径
- [ ] 测试路径验证
  - isValidAndroidDir() 检测标准结构

### Task 9.10: RootCopyStrategy 集成测试
- [ ] 创建 RootCopyStrategyTest.kt
- [ ] 使用 MockK 模拟 RootChecker
- [ ] 测试 executeBatchCopy 流程
  - 统计文件数
  - 准备目标目录
  - 执行复制（mock cp 命令）
  - 验证结果
- [ ] 测试看门狗
  - 进度上报
  - 超时处理
- [ ] 测试异常场景
  - cp 命令失败
  - 权限丢失
  - 磁盘满

### Task 9.11: ShizukuCopyStrategy 集成测试
- [ ] 创建 ShizukuCopyStrategyTest.kt
- [ ] 使用 MockK 模拟 ShizukuManager
- [ ] 测试 Shizuku 等待逻辑
  - 已授权但未连接 → 等待 2s
  - 超时后继续
- [ ] 测试复制流程（同 Root）
- [ ] 测试 Shizuku 特有逻辑
  - isServiceConnected 检查
  - executeCommandWithOutput 调用

### Task 9.12: NormalCopyStrategy 集成测试
- [ ] 创建 NormalCopyStrategyTest.kt
- [ ] 使用 Robolectric 模拟 Android 环境
- [ ] 测试并发复制
  - 动态并发度计算
  - IoOptimizer.fastCopy 调用
  - 增量检测逻辑
- [ ] 测试进度上报
  - 每 10 个文件更新一次
  - 速度计算（IoRateCalculator）

### Task 9.13: VerificationManager 单元测试
- [ ] 创建 VerificationManagerTest.kt
- [ ] 测试 Root 验证
  - 批量 stat 命令构建
  - 输出解析（"大小 路径"）
  - 结果比对
- [ ] 测试 Shizuku 验证（同 Root）
- [ ] 测试 Native 验证
  - 使用 File API 遍历
  - 并发统计
- [ ] 测试批次处理
  - 500 文件批次
  - 进度上报（90-100%）

### Task 9.14: StorageTypeDetector 单元测试
- [ ] 创建 StorageTypeDetectorTest.kt
- [ ] 测试存储类型检测
  - 检查 /sys/block/*/queue/rotational
  - Android R+ 默认 UFS
  - Android M-R 默认 UFS
  - 旧设备默认 eMMC
- [ ] 测试缓冲区推荐
  - SSD_UFS → 1MB
  - EMMC → 512KB
  - 根据内存调整
- [ ] 测试缓存机制
  - 首次检测后缓存
  - clearCache() 强制重新检测

### Task 9.15: NetworkClient 单元测试
- [ ] 创建 NetworkClientTest.kt
- [ ] 测试 OkHttpClient 单例
  - 双检锁定模式
  - 连接池配置（5 连接，5 分钟）
- [ ] 测试缓存配置
  - 10MB 缓存目录
  - 缓存拦截器
- [ ] 测试请求构建
  - createRequest() 添加 UA、Accept
  - ETag 支持（If-None-Match）
  - createDownloadRequest() Range 支持
- [ ] 测试日志拦截器（仅 Debug）

### Task 9.16: RuleEngine 集成测试
- [ ] 创建 RuleEngineTest.kt
- [ ] 使用 MockWebServer 模拟 HTTP
- [ ] 测试并发拉取
  - 多镜像节点竞速
  - 第一个成功返回
  - 剩余请求取消
- [ ] 测试缓存机制
  - ETag 304 处理
  - 本地缓存持久化
- [ ] 测试 JSON 解析
  - DynamicRule 对象映射
  - 错误处理

### Task 9.17: AppInstaller 单元测试
- [ ] 创建 AppInstallerTest.kt
- [ ] 测试 Omni-Installer 三模式
  - Root 优先：pm install -r
  - Shizuku 次优先：IPC 执行
  - Native 降级：Intent 跳转
- [ ] 测试 APK 文件验证
  - 不存在 → false
  - 无权限 → false
- [ ] 测试 FileProvider 兼容性
  - Android N+ 使用 content://
  - Android < N 使用 file://

### Task 9.18: 集成测试 - 完整替换流程
- [ ] 创建 EndToEndReplaceTest.kt
- [ ] 使用 Robolectric + TemporaryFolder
- [ ] 测试完整流程
  1. 用户选择源文件夹
  2. 权限检查（mock PermissionChecker）
  3. 创建 WorkRequest V2
  4. Worker 执行（模拟 CoroutineWorker）
  5. 进度回调（ReplaceProgressManager）
  6. 结果返回（SUCCEEDED/FAILED）
- [ ] 测试异常恢复
  - Worker 失败自动重试
  - 用户主动取消
  - 系统杀死后恢复

### Task 9.19: 性能基准测试
- [ ] 创建 PerformanceBenchmark.kt
- [ ] 测试 IoOptimizer.fastCopy
  - 对比 V1（普通 copy）vs V2（mmap）
  - 不同文件大小（1MB, 10MB, 100MB, 1GB）
  - 记录速度、CPU、内存
- [ ] 测试 ProgressTracker 开销
  - 1000 次 updateProgress 调用耗时
  - 节流效果验证
- [ ] 测试并发效率
  - 不同并发度（4, 8, 16, 32）性能对比
  - 找到最优并发数

### Task 9.20: 代码质量工具
- [ ] 配置 Detekt
  - 添加 gradle-detekt 插件
  - 配置规则集（complexity, codestyle, etc.）
  - 创建 baseline（忽略历史问题）
- [ ] 配置 Ktlint
  - 添加 org.jlleitschuh.gradle.ktlint 插件
  - 自动格式化（preBuild 任务）
  - 配置 .editorconfig
- [ ] 配置 CI 质量门禁
  - 覆盖率 < 80% → 失败
  - Detekt 严重问题 → 失败
  - 编译错误 → 失败

---

## 📊 验收标准

### 代码覆盖率（Jacoco）
- ✅ 核心模块 > 80%
  - IoOptimizer: 85%+
  - ProgressTracker: 90%+
  - FileReplaceOrchestrator: 80%+
  - CopyStrategy & implementations: 80%+
- ✅ 工具类 > 90%
  - FileHasher: 90%+
  - PathConstants: 95%+
- ✅ 集成测试覆盖关键路径 100%

### 代码质量
- ✅ Detekt 零严重问题（Complexity > 10, CodeSmell）
- ✅ Ktlint 零格式问题
- ✅ 所有测试通过（包括 CI）

### 性能基准
- ✅ 建立性能基线报告（V8.0.0 当前水平）
- ✅ V2 vs V1 性能对比数据（目标提升 30%+）

---

## 🔄 执行顺序

**Phase 1: 基础设施（Task 9.1 - 9.2）**
1. 添加 Gradle 依赖
2. 配置 Jacoco
3. 创建测试数据工厂
4. 配置 CI

**Phase 2: 核心模块测试（Task 9.3 - 9.9）**
5. ProgressTracker + SpeedCalculator
6. FileStatistics + FileHasher
7. IoOptimizer + CopyConfig
8. PathConstants + StorageTypeDetector

**Phase 3: 策略测试（Task 9.10 - 9.12）**
9. RootCopyStrategy
10. ShizukuCopyStrategy
11. NormalCopyStrategy

**Phase 4: 支持模块测试（Task 9.13 - 9.17）**
12. VerificationManager
13. NetworkClient
14. RuleEngine
15. AppInstaller

**Phase 5: 集成与性能（Task 9.18 - 9.19）**
16. 端到端集成测试
17. 性能基准测试

**Phase 6: 质量工具（Task 9.20）**
18. Detekt + Ktlint
19. CI 质量门禁

---

## 📝 备注

- 所有测试使用 **Kotlin** 编写（与生产代码一致）
- 使用 **MockK** 进行 Mock（Kotlin 友好）
- 使用 **Turbine** 测试 Flow（最佳实践）
- 使用 **Robolectric** 模拟 Android 框架（避免真机）
- 测试命名规范：`ClassNameTest.kt`，方法名 `should[ExpectedBehavior]When[Condition]`
- 每个测试类使用 `@OptIn(ExperimentalCoroutinesApi::class)` 允许使用 `runTest`

---

**准备开始执行！** 🚀