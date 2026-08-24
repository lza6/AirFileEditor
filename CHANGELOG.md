# Changelog

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
