# V12.0.0 架构升维 (Clean Architecture & DDD) - 已完成 ✅

## ✅ 已完成
- [x] **Domain 层建立**:
  - [x] 定义核心业务实体 (AccessMode, TaskPhase, PermissionStatus)
  - [x] 抽象 ConfigRepository 仓储接口
  - [x] 提取核心业务用例 (ReplaceFileUseCase, CheckEnvironmentUseCase, ManagePatchUseCase, ManageFileTimeUseCase)
- [x] **Data 层实现**:
  - [x] 建立 ConfigRepositoryImpl，封装 Manager 逻辑
  - [x] 统一任务进度与权限流的分发
- [x] **UI 层解耦**:
  - [x] ReplacingViewModel 迁移至 UseCase 驱动模式
  - [x] 建立 ReplacingViewModelFactory 实现手动依赖注入
  - [x] MainActivity 适配新架构
- [x] **物理模块化拆分**:
  - [x] `:core` 模块 - 基础设施层 (Shizuku, Worker, Performance, Utils, Manager)
  - [x] `:domain` 模块 - 纯业务逻辑层 (Model, Repository 接口, UseCase)
  - [x] `:data` 模块 - 数据访问层 (Repository 实现, Preferences)
  - [x] `:app` 模块 - UI 展示层 (Activity, Compose, ViewModel)
- [x] **测试迁移**:
  - [x] UseCase 测试迁移至 `:domain` 模块
  - [x] Worker/Orchestrator 测试迁移至 `:core` 模块
  - [x] 添加必要的测试依赖 (junit, mockk, coroutines-test, robolectric)

## 📋 备注
- 依赖方向严格遵守 Clean Architecture: `:app` → `:data` → `:domain` ← `:core`
- AIDL 生成的接口文件已统一在 `:core` 模块
- PerformanceMonitor 和 MetricCollector 已集成 APM 监控体系
