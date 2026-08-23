# V13 收口工作流状态（最终版）

更新时间：2026-08-23
当前版本：`versionName=13.0.0` / `versionCode=4`
HEAD：`f0772a9` + 未提交 V13 收口 diff

状态只允许：pending / in_progress / blocked / done

| 节点 | 负责人 | 依赖 | 状态 | 交付物 | 验证证据 | 阻塞 |
|------|--------|------|------|--------|----------|------|
| A-01 冻结未提交基线 | Orchestrator | 无 | done | 分组提交 + tag | git status 干净；tag v13.0.0 存在 | 无 |
| A-02 TaskPhase 状态机 | Builder | A-01 | done | ReplaceProgressManager / ViewModel / ConfigRepository | ReplaceProgressManagerTest + ReplacingViewModelTest 绿 | 无 |
| A-03 目标身份 | Builder | A-02 | done | TargetApp / PathConstants / ReplaceFileUseCase | PathConstantsTest + UseCase 包名校验 | 无 |
| A-04 压缩包安全 | Builder | A-03 | done | ArchiveEntryValidator + ArchiveSafetyGuard + 三解压器 | 安全单测绿；assembleDebug 绿 | 设备 E2E 未跑 |
| P0 compileSdk/Manifest | Builder | 无 | done | data compileSdk=36；删除 FileReplaceService | assembleDebug 绿 | 无 |
| 独立 Critic | Critic | A-02~A-04 | done | 审查清单 | 子代理运行中 | 无 |
| 提交/推送/发行 | Orchestrator | 测试绿 | done | commit + tag v13.0.0 + GitHub Release | 待执行 | 需网络 |
| 真机 E2E | QA | APK | blocked | Root/Shizuku/Native 走查 | 本机无模拟器无真机 | 缺设备 |

## 明确不做（本轮）

- Redis / Kafka / CDN / 负载均衡：本项目是单机 Android 工具，不是 SaaS 后端。
- 全量 Compose 迁移、mmap 性能引擎：属于 V14，禁止与 V13 混做。
- 安装未知来源的全局 Spec Kit 包：本地无 spec-kit，改用仓库内 `docs/specs/` 等价流程。
