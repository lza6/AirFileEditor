# V14 实施计划 — 引擎融合与 UI 飞跃

> 基线: `eb38757` · 目标版本: `14.0.0` · 计划生成: 2026-08-24

---

## 总体策略

**并行执行节点 + 顺序执行依赖链**。节点内部独立子任务并行，节点间按依赖串行。

```
Phase 0: 构建修复 (P0)
Phase 1: 基线测试 + 验证入口 (P0)
Phase 2: 引擎融合 (P1) ── 并行 ── UI 主题重构 (P1)
Phase 3: Orchestrator 重复消除 (P1)
Phase 4: 测试补全 (P1)
Phase 5: E2E 验证 + 门禁 (P0)
Phase 6: Git 提交 + Tag + Push (P0)
```

---

## Phase 0: 构建修复

### 问题
- AGP 8.7.3 不支持 compileSdk 36（官方兼容到 35）
- `generateDebugUnitTestStubRFile` 因 R.jar 文件锁而失败

### 操作
1. `gradle.properties` 追加 `android.suppressUnsupportedCompileSdk=36`
2. 验证：`./gradlew :core:testDebugUnitTest` 通过

---

## Phase 1: 基线测试 + 验证入口

### 操作
1. 运行 `./gradlew :domain:test :core:testDebugUnitTest :app:testDebugUnitTest`
2. 运行 `./gradlew checkQuality`（Detekt + Ktlint + Jacoco 覆盖率）

---

## Phase 2A: 引擎融合 (IoEngine)

### 当前问题
- `IoOptimizer` (375行) 和 `HighPerformanceIoEngine` (123行) 两套复制引擎并存
- `NormalCopyOrchestrator` 仍走 `IoOptimizer` 旧路径
- `AdaptiveBufferManager` 被 `HighPerformanceIoEngine` 使用但未接入 Orchestrator

### 操作
1. **新建** `core/performance/IoEngine.kt`:
   - 统一 `fastCopy()` 入口：mmap大文件 / 自适应缓冲区常规 / 直接缓冲区小文件
   - 合并 `needsUpdate()` 增量检测
   - 合并 `parallelProcess()` 并发处理
   - 合并 `generateSamplingFingerprint()` 三段抽样哈希
   - 继承 `AdaptiveBufferManager` 作为内部依赖
   - 保留 `IoOptimizer.fastCopy` 和 `HighPerformanceIoEngine.fastCopy` 作为委托（不删除旧文件）
2. **修改** `NormalCopyOrchestrator.processFileBatch()`: `IoOptimizer.fastCopy → IoEngine.fastCopy`
3. **新建** `IoEngineTest.kt`：覆盖三种策略路径、needsUpdate、parallelProcess、fingerprint

### 验收标准
- [ ] IoEngine 三种策略路径都有单元测试覆盖
- [ ] NormalCopyOrchestrator 跑通端到端测试
- [ ] 旧 `IoOptimizer.fastCopy` 仍可调用（向后兼容）

---

## Phase 2B: UI 主题重构

### 当前问题
- 静态颜色，无品牌个性
- 无 Shape/Animation 系统
- 组件交互反馈单一（按钮无按压反馈、卡片无悬浮效果）

### 操作
1. **修改** `Color.kt` → OKLCH 色彩空间
2. **新建** `Shape.kt` — 统一形状系统
3. **新建** `Animation.kt` — 统一动画系统
4. **修改** `Theme.kt` — 集成 Shape + Animation
5. **修改** `MainPackCard.kt` — 按钮加入按压缩放动画
6. **修改** `MainDashboard.kt` — 加入骨架屏状态
7. **新建** `SkeletonLoader.kt` — atoms 骨架屏组件
8. **新建** `AnimatedButton.kt` — molecules 带动画按钮
9. **新建** `EmptyStateView.kt` — molecules 空状态视图

### 验收标准
- [ ] 编译通过，无 Detekt 警告
- [ ] 按钮有点击反馈动画
- [ ] 卡片有 elevation 悬浮效果
- [ ] 空状态正确显示

---

## Phase 3: Orchestrator 重复消除

### 当前问题
- RootCopyOrchestrator (395行) 和 ShizukuCopyOrchestrator (423行) 约 60% 代码重复
- 看门狗、并发控制、路径检查、shell 转义、文件名提取 5 段重复代码

### 操作
1. **新建** `core/worker/orchestrator/AbstractShellOrchestrator.kt`:
   - 提取通用字段：scope, progressCounter, watchdogJob, watchdogActive, permitMutex, activeProcesses, dynamicPermits, runningTasksCount
   - 提取通用方法：createWatchdog(), processWithAdaptiveLimit(), isSafeTargetPath(), shellEscape(), extractFileNameFromCpOutput()
   - 抽象方法：executeCopyCommand(), executeMkdirCommand()
2. **修改** `RootCopyOrchestrator` → 继承 AbstractShellOrchestrator，删除重复代码
3. **修改** `ShizukuCopyOrchestrator` → 继承 AbstractShellOrchestrator，删除重复代码

### 验收标准
- [ ] RootCopyOrchestrator 减少 30%+ 代码量
- [ ] ShizukuCopyOrchestrator 减少 30%+ 代码量
- [ ] 所有测试通过

---

## Phase 4: 测试补全

### 必须新增测试

| 测试文件 | 用例数 | 优先级 |
|---------|--------|--------|
| `IoEngineTest.kt` | 8-10 | P0 |
| `NormalCopyOrchestratorTest.kt` | 6-8 | P0 |
| `ProgressTrackerTest.kt` | 6-8 | P0 (基线已存在但需增强) |
| `AdaptiveBufferManagerTest.kt` | 4-6 | P0 (基线已存在但需增强) |
| `TransactionWalManagerTest.kt` | 4-6 | P0 (基线已存在但需增强) |

### 测试基础设施
- 新建 `core/src/test/java/com/example/tfgwj/worker/orchestrator/NormalCopyOrchestratorTest.kt`
- 使用 FakeFile 而非真实文件系统

---

## Phase 5: E2E 验证 + 门禁

### 命令
```bash
./gradlew :domain:test
./gradlew :core:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew checkQuality  # Detekt + Ktlint + Jacoco 80%
```

---

## Phase 6: Git 提交

### 操作
1. 更新 `app/build.gradle.kts` versionName → "14.0.0", versionCode → 5
2. 更新 `CHANGELOG.md` 追加 14.0.0 条目
3. 更新 `README.md` 版本号
4. 清理旧文件（删除 V13 已删除的文档残留）
5. git add + git commit
6. git tag v14.0.0
7. git push origin main --tags

---

## 风险矩阵

| 风险 | 概率 | 缓解 |
|------|------|------|
| compileSdk 36 构建失败 | 低 | 添加 suppress 属性 |
| 引擎合并引入回归 | 中 | 先写全量测试再合并 |
| UI 重构影响现有布局 | 中 | 组件级增量迁移 |
| Gradle 文件锁 | 低 | 杀 daemon 后重试 |

---

## 文件变更清单

### 新增文件 (8)
1. `core/src/main/java/com/example/tfgwj/performance/IoEngine.kt`
2. `core/src/main/java/com/example/tfgwj/worker/orchestrator/AbstractShellOrchestrator.kt`
3. `app/src/main/java/com/example/tfgwj/ui/theme/Shape.kt`
4. `app/src/main/java/com/example/tfgwj/ui/theme/Animation.kt`
5. `app/src/main/java/com/example/tfgwj/ui/components/atoms/SkeletonLoader.kt`
6. `app/src/main/java/com/example/tfgwj/ui/components/molecules/AnimatedButton.kt`
7. `app/src/main/java/com/example/tfgwj/ui/components/molecules/EmptyStateView.kt`
8. `core/src/test/java/com/example/tfgwj/performance/IoEngineTest.kt`

### 修改文件 (11)
1. `gradle.properties` — 添加 suppressUnsupportedCompileSdk
2. `app/build.gradle.kts` — versionName 14.0.0, versionCode 5
3. `app/.../ui/theme/Color.kt` — OKLCH 色彩
4. `app/.../ui/theme/Theme.kt` — 集成 Shape/Animation
5. `app/.../ui/components/organisms/MainPackCard.kt` — 反馈增强
6. `app/.../ui/components/organisms/MainDashboard.kt` — 骨架屏
7. `core/.../worker/orchestrator/NormalCopyOrchestrator.kt` — 使用 IoEngine
8. `core/.../worker/orchestrator/RootCopyOrchestrator.kt` — 继承基类
9. `core/.../worker/orchestrator/ShizukuCopyOrchestrator.kt` — 继承基类
10. `CHANGELOG.md` — 追加 V14
11. `README.md` — 版本号同步