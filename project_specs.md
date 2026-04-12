# Project Specifications: tfgwj (听风改文件) V9.0.0 (Quality Foundation)

## 1. 项目愿景 (Vision)
打造业界领先的 Android 文件自动化管理工具，专注于高效、安全、无痛的 `/Android/data` 目录文件替换与优化。

## 2. 核心功能 (Core Features)

### Omni-Mode 智能检测系统 (v3.1.0 核心)
- **自动模式识别**: 智能检测 Root、Native、Shizuku 三种访问模式
- **物理验证机制**: 对每种模式进行实际的文件读写测试
- **最佳模式选择**: 根据设备环境和权限状态自动选择最优方案
- **手动模式切换**: 提供模式选择对话框，允许高级用户手动指定

### 高性能 IO 引擎 (v3.1.0 核心)
- **IoOptimizer 优化引擎**: 动态缓冲区管理 + 并发控制
- **NIO Zero-Copy**: 接近硬件极限的文件复制速度
- **增量更新算法**: MD5 哈希比对，只更新变化的文件
- **智能缓存管理**: 针对 UE4 游戏的 .pixuicache 优化

### 多格式支持
- **解压引擎**: 7z、Zip、Rar 多格式支持
- **流式处理**: 大文件解压不占用过多内存
- **加密压缩包**: 支持自动密码识别

### 文件时间管理
- **随机时间**: 一键随机化文件修改时间
- **时间锁定**: 锁定指定时间戳
- **时间应用**: 批量应用时间到目标文件

### 用户体验
- **全异步执行**: 基于 WorkManager 与协程
- **悬浮球监控**: 后台任务状态实时显示
- **智能日志**: 结构化日志系统
- **Material 3 UI**: 现代化界面设计

## 3. 技术栈 (Tech Stack)

### 核心技术
- **语言**: Kotlin (1.9.0+)
- **架构**: 响应式管理器 (Manager Pattern) + WorkManager
- **异步处理**: Kotlin Coroutines + Flow

### 关键库
- **Shizuku**: 跨进程文件访问（Root 替代方案）
- **DataStore**: 持久化偏好设置
- **WorkManager**: 后台任务管理
- **Material Components**: UI 组件库
- **Zip4j**: Zip 格式解压
- **Apache Commons Compress**: 7z/Rar 格式解压

### 性能优化
- **NIO (New IO)**: 零拷贝文件传输
- **Semaphore**: 并发控制
- **对象池**: 缓冲区复用
- **协程调度器**: 线程池优化

## 4. 关键指标 (KPIs)

### 性能指标
- **文件复制速度**: P99 延迟接近硬件 IO 极限（100MB/s+ 针对 UFS 3.1）
- **解压速度**: 7z 解压速度 > 50MB/s
- **内存占用**: 峰值控制在 150MB 以内
- **并发性能**: 16 并发处理，线性扩展

### 可靠性指标
- **成功率**: 替换任务成功率 > 99.5%
- **自动重试**: 失败自动重试机制
- **异常处理**: 完善的错误捕获和日志记录

### 用户体验指标
- **响应时间**: UI 操作响应时间 < 100ms
- **权限检测**: Omni-Mode 检测时间 < 3s
- **任务启动**: Worker 启动时间 < 500ms

## 5. 系统架构

### 核心模块
```
MainActivity (UI 层)
    ↓
PermissionManager (权限管理)
    ↓
PermissionChecker (权限检测)
    ↓
Omni-Mode System (智能模式选择)
    ↓
┌─────────────┬─────────────┬─────────────┐
│  Root Mode  │ Native Mode │ Shizuku Mode│
└─────────────┴─────────────┴─────────────┘
    ↓
FileReplaceWorker (后台任务)
    ↓
IoOptimizer (IO 优化)
    ↓
文件操作 (复制/解压/时间修改)
```

### 数据流
```
用户操作 → UI 事件 → 管理器处理 → 后台任务 → IO 操作 → 结果反馈
```

## 6. 版本历史

### V8.0.0 (已完成 - Orchestrator Architecture Refactoring)
- ✅ 架构演进：FileReplaceWorker 模块化拆分
  - ✅ 创建 `FileReplaceOrchestrator` 接口，定义统一契约
  - ✅ 实现 `RootCopyOrchestrator` / `ShizukuCopyOrchestrator` / `NormalCopyOrchestrator`
  - ✅ 提取 `ProgressTracker` 统一双级节流（WM 1000ms / UI 32ms）
  - ✅ 提取 `FileStatistics` 管理文件扫描与批次生成
  - ✅ 提取 `VerificationManager` 实现批量验证逻辑
  - ✅ 提取 `PathConstants` 消除硬编码路径
  - ✅ 提取 `CopyConfig` 动态配置并发度与节流参数
  - ✅ 创建 `FileReplaceWorkerV2` 作为新版入口（V1 保留向后兼容）
- ✅ MainActivity 集成 V2 架构（默认使用 createWorkRequestV2）
- ✅ 文档更新与向后兼容性维护
- ⚠️ 待完成：单元测试（ProgressTracker + Orchestrator）

### V7.0.0 (已完成 - Performance & Architecture Evolution)
- ✅ 网络层升级：HttpURLConnection → OkHttp (连接池、HTTP/2支持)
- ✅ 断点续传：UpdateManager支持Range请求，中断后可继续
- ✅ 存储类型检测：StorageTypeDetector识别SSD/UFS vs eMMC，智能调整缓冲区
- ✅ 进度预测：ProgressPredictor预估剩余完成时间
- ✅ 新增NetworkClient工具类：统一OkHttpClient管理

### V6.0.0 (已完成 - Phantom Core & mmap)
- ✅ 架构重构：基于 mmap 的极速内存映射写入 (Zero-Copy)
- ✅ 安全屏蔽：防连带环境侦测自清模块 (Phantom Stealth)
- ✅ 规则解耦：通过 GitHub Mirror 构建异步 JSON 规则链 (由 RuleEngine.fetchCloudRules() 实现)

### V5.0.0 - V5.2.0 (已完成 - OTA 与防劫持网络层)
- ✅ 搭载基于协程与 HttpURLConnection 的零依赖下载流
- ✅ 并发多线程代理节点竞速，突破封锁与地域歧视
- ✅ 防连带污染：防反向代理篡改的 HTTPS 原生降级机制 (Proxy Shield)
- ✅ 底层安装体系并入 Omni-Installer (Root/Shizuku/Native)

### V4.0.0 (已完成)
- ✅ Omni-Mode 极限强化 (主动 Watchdog 机制)
- ✅ IoOptimizer 抽样哈希 (Sampling Hash)
- ✅ Shizuku 进程级进度双节流 (IPC Throttling)

### v3.1.0 
- ✅ Omni-Mode 智能检测系统

## 7. 待办路线图 (Roadmap - project_specs.md)

### V8.0.0 核心任务库 (Orchestrator Architecture Refactoring) - 已完成 ✅
- [x] **Task 11: 创建 FileReplaceOrchestrator 接口与核心抽象** (V8.0.0 完成)
- [x] **Task 12: 实现三种模式 Orchestrator (Root/Shizuku/Normal)** (V8.0.0 完成)
- [x] **Task 13: 创建 FileReplaceWorkerV2 并集成 Orchestrator** (V8.0.0 完成)
- [ ] **Task 14: 单元测试 - ProgressTracker 节流验证** (待完成)
- [ ] **Task 15: 单元测试 - Orchestrator 端到端测试** (待完成)
- [x] **Task 16: 更新 MainActivity 集成 V2 架构** (V8.0.0 完成)
- [x] **Task 17: 文档更新与向后兼容性维护** (V8.0.0 完成)
  - ✅ V1 保留作为降级方案（createWorkRequest）
  - ✅ 更新 project_specs.md 记录架构演进
  - ✅ 无需删除 V1 代码，确保向后兼容

### V10.0.0 (已完成 ✅ - Performance Engine & APM)
- ✅ **极致性能引擎**:
  - ✅ **自适应并发调度**: 引入 `AdaptivePermitScheduler`，基于 CPU 负载、内存压力及 IO 速度实时调整并发度。
  - ✅ **小文件聚合优化**: 针对极小文件（<1KB）分流处理，减少系统调用开销。
  - ✅ **动态并发软限制**: 采用 `permitMutex` + `runningTasksCount` 机制，彻底解决 Semaphore 实例替换导致的竞态风险。
- ✅ **APM 性能监控**:
  - ✅ **细粒度指标采集**: 实现 IO 写入物理延迟（IO Wait）与 Shizuku Binder 调用时延（IPC Latency）的精准记录。
  - ✅ **性能诊断系统**: 提供基于多维度指标的自动化优化建议。
- ✅ **全架构适配**: Root/Native/Shizuku 模式全面实装自适应并发与 APM 监控。
- ✅ **V11 预备工作**: 
  - ✅ Compose UI 组件骨架（Atomic Design: Atoms/Molecules/Organisms）
  - ✅ MVI 架构基础（ReplacingIntent/State/ViewModel）
  - ✅ 为 MainActivity 重构铺平道路

### V9.0.0 (已完成 - Quality Foundation)
- ✅ 测试基础设施：Jacoco/Detekt/Ktlint 全量配置
- ✅ 核心单元测试：SpeedCalculator, ProgressTracker, FileHasher, IoOptimizer
- ✅ 质量门禁：Detekt Baseline + Ktlint Auto-format
- ✅ CI/CD：GitHub Actions 自动化流水线
- ✅ 覆盖率验证：Jacoco 80% 目标建立

## 8. 未来演进愿景 (V10 - V15)
详细计划见 [`plans/ULTIMATE_EVOLUTION_GUIDE.md`](plans/ULTIMATE_EVOLUTION_GUIDE.md)

- **V10 (APM & Performance)**: 极致 IO 监控与 Zero-Copy 2.0。
- **V11 (Jetpack Compose)**: UI 全面现代化重构，彻底解决 MainActivity 冗余。
- **V12 (DDD & Modularization)**: 领域驱动设计，多模块解耦。
- **V13 (Advanced Stealth)**: 隐匿性增强，防范厂商侦测。
- **V14 (AI Agent Integration)**: 集成端侧大模型，实现自然语言管理文件。
- **V15 (Universal Core)**: 跨平台核心库 (KMP)，支持除 Android 外的更多系统管理。

### 难点 1: Android 11+ 的分区存储限制

**解决方案**: 使用 Shizuku 提供的跨进程访问能力，绕过分区存储限制。

### 难点 2: 大文件解压导致 OOM
**解决方案**: 使用流式解压 + 动态缓冲区管理，避免一次性加载整个文件到内存。

### 难点 3: 多种权限环境的兼容性
**解决方案**: Omni-Mode 智能检测系统，自动识别并选择最佳访问模式。

### 难点 4: 后台任务被系统杀死
**解决方案**: 使用 WorkManager 的持久化机制 + 前台服务 + 悬浮球保活。

### 难点 5: 文件复制速度慢
**解决方案**: NIO Zero-Copy + 并发控制 + 增量更新算法。

## 9. 测试策略

### 单元测试
- [ ] 核心算法测试（MD5、哈希比对）
- [ ] 权限检测逻辑测试
- [ ] IO 优化器测试

### 集成测试
- [ ] 完整替换流程测试
- [ ] 多模式切换测试
- [ ] 异常场景处理测试

### 性能测试
- [ ] 大文件处理性能测试
- [ ] 并发处理压力测试
- [ ] 内存占用监控测试

### 兼容性测试
- [ ] 不同 Android 版本测试（10-15）
- [ ] 不同设备厂商测试
- [ ] Root/非 Root 环境测试

## 10. 开发规范

### 代码规范
- 遵循 Kotlin 官方编码规范
- 使用 Material Design 3 设计规范
- 代码注释使用中文，关键逻辑添加英文注释

### Git 规范
- 分支管理: main（主分支）、dev（开发分支）、feature/*（功能分支）
- 提交规范: feat/fix/docs/style/refactor/test/chore
- 提交信息: 使用 Conventional Commits 规范

### 文档规范
- README: 面向普通用户，简洁易懂
- API 文档: 面向开发者，详细完整
- 设计文档: 面向架构师，深入浅出