# Spec: V14 Tighten — 终局闭环审计与盲点扫描

## Status: draft
## Created: 2026-08-24

## Overview
V14 已提交 (`ddfc17b`) 并打 tag `v14.0.0`，但存在以下已知未闭环点，本次 Spec 锁定 V15 前置的"终局收紧"范围：

1. **IoEngineTest 未落盘** — V14 计划声称新增但实际未创建对应测试文件
2. **UI 组件未接线** — SkeletonLoader/CardSkeleton/AnimatedButton/EmptyStateView 已创建但未被任何现有页面引用（假功能）
3. **误提交本地 AI 配置** — `.claude/settings.json`、`opencode.json`、`.mcp.json`、`AGENTS.md`、`.ignore`、`test-worker-cmdline.txt` 被 `git add -A` 带入 V14 提交，属残缺产物需回退
4. **JaCoCo 覆盖率门禁未正式验收** — `checkQuality` 未在 V14 跑通终验
5. **替换历史 UI 缺失** — `getReplaceHistory()` 数据流已存在但无 UI 消费入口（V15 主项，本次规划）
6. **Windows Gradle 文件锁** — `output.bin` 偶发删除失败，需稳定化
7. **代码库盲点** — 多文件 700+ 行、`ReplaceProgressManager` 全局单例、日志轮询、重复类等历史技术债未全面盘点

## Functional Requirements
- FR1: IoEngine 三种复制策略各有 ≥2 条单元测试（小文件/大文件/自适应 + needsUpdate + sampling + parallelProcess）
- FR2: UI 新组件被真实页面引用（骨架屏进 MainDashboard/权限卡，EmptyState 进小包列表）
- FR3: 移除本地 AI 配置出入库，新增 `.gitignore` 规则防止复发
- FR4: `checkQuality`(Detekt+Ktlint+JaCoCo 行≥80%/分支≥70%) 真实通过
- FR5: 替换历史 UI 入口闭环（列表/详情/删除/清空）
- FR6: Windows 构建锁问题缓解（构建脚本层 doFirst 清理旧结果）

## Technical Constraints
- Kotlin 2.0.21 / AGP 8.7.3 / JVM 11 / minSdk 26 / compileSdk 36
- 依赖方向 `:app → :data → :domain ← :core`
- `:core` 单元测试用 Robolectric（Android Log 依赖）
- 不引入新依赖除非确需

## Data Model
- 无新表；沿用 `ReplaceHistoryItem` 领域模型（timestamp/packageName/sourcePath/targetPath/totalFiles/successCount/failedCount/errors/backupPath）

## Edge Cases & Error Handling
- 历史列表空态（EmptyStateView）
- 大历史量滚动性能（LazyColumn + key）
- 删除单条/清空竞态（乐观更新 + 失效重载）
- IoEngine 复制失败降级（mmap→缓冲流→返回0）
- Windows 文件锁（测试前 doFirst 清理 + 文档披露）

## Security Considerations
- 历史记录不落日志明文（仅在 UI 展示）
- 路径拼接必须走 `isSafeTargetPath`/`PathConstants.resolveTargetFile`

## Testing Strategy
- IoEngineTest：10-12 用例全绿
- 历史 UI：ViewModel 层单测（FakeRepository）
- `checkQuality` 全绿 + 留存日志

## Non-Functional Requirements
- 构建稳定性：core 测试可重复 `BUILD SUCCESSFUL` ≥2 次
- 文档一致性：README/CHANGELOG/下一步改进指南与 v14.0.0 同步
- 代码库健康：新变更多文件 <800 行，函数 <50 行

## Implementation Approach
Phase 0: 回退误提交本地配置 + 补 .gitignore（已在做）
Phase 1: IoEngineTest 落盘 + 全绿
Phase 2: UI 组件接线（MainDashboard 骨架屏 + EmptyState 小包空态 + AnimatedButton 关键按钮）
Phase 3: 替换历史 UI（Screen + ViewModel + 列表/详情 + 删除/清空）
Phase 4: checkQuality 门禁终验 + 日志留存
Phase 5: 全量回归 + 文档同步 + 独立审查

## Acceptance Criteria
- [ ] IoEngineTest ≥10 用例全绿且被 JaCoCo 计入
- [ ] 骨架屏/空态被真实引用（grep 命中 ≥2 处）
- [ ] 本地 AI 配置从 git 追踪移除，`.gitignore` 含对应规则
- [ ] `checkQuality` BUILD SUCCESSFUL
- [ ] 历史 UI 可浏览（列表+详情+删除+空态）
- [ ] 独立审查发现 0 个 P0/P1
- [ ] README/CHANGELOG 与 v14 一致
- [ ] 新提交 `v14.1.0`（或 v15）合并后 push + tag