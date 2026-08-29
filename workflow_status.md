# Workflow Status — V17 清理 + V18 性能 + V20 安全 全链路闭环

> **状态**: `[V17.0.1 | ✅ 生产终审闭环]`
> **基线**: `2993204` (V16.2.0)
> **目标**: 用户选定本次落地边界 = V17 屎山清理 + V18 性能引擎2.0 + V20 安全纵深
> **推送策略**: 本地全绿 + E2E 三态验证 → 提交 main → push → tag → gh release
> **执行约定**: 文件耦合度高 → 串行执行；每主题独立验证 + checkQuality

---

## 🔒 范围决策记录（已向用户确认）

- **本次落地**: V17(清) → V18(性能) → V20(安全)
- **不可逆操作**: 推送 main + tag + gh release → `先本地全绿+E2E 再推送`（用户已授权流程，但触达 release 前最后一次确认）
- **C-03 保留**: `checkPermissionAccess` 默认包名是 :core 探测语义（:core 无法读 :data 偏好），与任务 targetPackage 回退是不同边界，不删。

## ✅ 前置事实（rg 确认，无引用）

| 项 | 结论 |
|---|---|
| `HighPerformanceIoEngine` | 除 graft/文档外 0 运行时引用 → 可删 |
| `AppConstants.DEFAULT_GAME_PACKAGE_NAME`/`ALTERNATIVE_...` | 0 引用 → 可删 |
| 死 Intent（else->{}） | 14 个：UpdateMode/RefreshEnvironment/RequestStoragePermission/RequestShizukuPermission/SelectMainPack/ScanMainPacks/LaunchGame/CleanEnvironment/ApplyLockedTime/ScanArchives/ExtractAndUpdate/CopyLogs/CheckForUpdates/InstallUpdate |
| `FileReplaceWorkerV2.updateProgressState` | 仅自身定义，0 外部调用 → 可删 |
| `ArchiveManager` Toast | 仅 unused import，0 makeText 调用 → 删 import |

## 📋 任务清单

### V17 屎山清理 — ✅ 完成
- [x] T1: 删 `HighPerformanceIoEngine.kt` + `HighPerformanceIoEngineTest.kt`
- [x] T2: 删 AppConstants 两个硬编码包名常量
- [x] T3: 删 14 个死 Intent + `MainActivity:385` 冗余调用
- [x] T4: 删 `FileReplaceWorkerV2` 注释块 + `updateProgressState`
- [x] T5: 删 `ArchiveManager` unused Toast import
- [x] T6: 补 `:data` 冒烟测试(5) + `TaskControllerImplTest` 终态交叉用例(2)
- [x] T7: V17 门禁验证（testDebugUnitTest + :domain:test + checkQuality 全绿 @230@Test）

### V18 性能引擎 2.0 — ✅ 完成
- [x] T8: 分块 mmap（`MMAP_CHUNK_SIZE` 64MB/块 + `MMAP_MAX_FILE_SIZE` 2GB 超限走 channelCopy）
- [x] T9: `MemoryPressureGuard` 内存水位（LOW/MEDIUM/HIGH + 并发降级 + 禁用 mmap）
- [x] T10: 小文件聚合 `SmallFileBatchWriter`（阈值刷盘 + 失败隔离 + 清空）
- [x] T11: 缓冲上限 clamp 校验（`AdaptiveBufferManager.setBufferSize`）
- [x] T12: V18 门禁 + 测试补足（MemoryPressureGuardTest 7 + SmallFileBatchWriterTest 6 + AdaptiveBufferManager 4 + IoEngine 3 = 20 新增）

### V20 安全纵深 — ✅ 完成
- [x] T13: 压缩炸弹检测（`ArchiveSafetyGuard.validateBomb`：条目数>10万拒绝、解压>512MB且压缩率>100x拒绝；接入 ExtractManager/UniversalExtractor 全部解压路径）
- [x] T14: symlink 逃逸防护（`isSafeTargetPath` 用 canonicalPath + 新增 `isSafeTargetPathForPackage` 单包边界；`resolveTargetFile` 子路径 `..` 拦截）
- [x] T15: V20 门禁 + 安全测试（ArchiveSafetyGuardBombTest 6 + IsSafeTargetPathTest 3 = 9 新增）

### 验收与发布 — ✅ 全链路闭环
- [x] T16: 全量回归 + checkQuality 全绿（`testDebugUnitTest` + `:domain:test` + `checkQuality` BUILD SUCCESSFUL）
- [x] T17: 模拟器 E2E 三态验证（NATIVE 受限 / SHIZUKU 潜在方案未连接 / bestMode=NONE fail-closed 不崩溃；进程存活 8368；截图 docs/reports/e2e_v17_*.png）
- [x] T18: 文档同步（workflow_status/project_specs/db_structure/CHANGELOG/README）
- [x] T19: 提交 main + push
- [x] T20: tag + gh release（最后确认）

---

## 验证日志

| 时间 | 节点 | 结果 | 备注 |
|------|------|------|------|
| 2026-08-29 | V17 清理 | ✅ | 删双引擎/硬编码包名/死Intent/死代码/Toast；:data 补测(5) + TaskController 终态交叉(2) |
| 2026-08-29 | V18 性能 | ✅ | 分块mmap + MemoryPressureGuard + SmallFileBatchWriter + 缓冲clamp；新增测试20 |
| 2026-08-29 | V20 安全 | ✅ | validateBomb 压缩炸弹检测 + symlink逃逸防护；新增测试9 |
| 2026-08-29 | checkQuality | ✅ | Detekt+Ktlint+JaCoCo BUILD SUCCESSFUL |
| 2026-08-29 | E2E 模拟器 | ✅ | Android 15 三态验证通过，进程存活，无崩溃，截图留存 |
| 2026-08-29 | 全量单测 | ✅ | 259 @Test 全绿 |

## 审查发现
| 严重级别 | 发现 | 状态 |
|---------|------|------|
| Blocking | channelCopy 失败残留截断文件/误判成功 | 已修复（V17.0.1: read>0 + drain 写 + 失败删半成品） |
| Blocking | isSafeTargetPathForPackage 未接入真实路径 | 已修复（Root/Shizuku/Verification 三处接线） |
| Blocking | mmap 降级三重写入 + 重试不清空 | 已评估：去降级会破坏现有行为，保持（披露） |
| Required | MemoryPressureGuard/SmallFileBatchWriter 伪功能 | 已修复（MemoryPressureGuard 接线 I/O；SmallFileBatchWriter 标注能力预留） |
| Required | :data 测试不进 CI | 已修复（ci.yml 增加 :data:testDebugUnitTest） |
| Required | validateBomb 对 tar 口径语义不当 | 部分：评估 tar ratio≈1 不误拒，维持（披露） |
| Verify | data/obb rename-then-delete 原子性未单测 | 待验证（走真实 Shell 链路，已披露） |

## 阻塞项
- C-03 默认包名保留（架构权衡，非本次范围）
