# tfgwj (听风改文件 AirFileEditor)

Android 文件管理增强工具:通过 Root / Shizuku / 普通三种模式替换、解压目标应用(如游戏)的 `Android/(data|obb)` 目录文件。当前版本 V13(见 `app/build.gradle.kts` 的 versionName)。

历史设计文档在 `plans/` 目录,其中 `plans/V13-收口基线说明.md` 是当前架构契约的唯一验收入口。

## 关键规则(不可违反)

以下契约已被单测锁定,修改前先跑测试:

- **TaskPhase 是任务状态唯一权威源**:`domain/model/DomainModels.kt` 定义 7 状态(IDLE/PREPARING/REPLACING/VERIFYING/COMPLETED/FAILURE/CANCELLED)。COMPLETED/FAILURE/CANCELLED 是终态,终态不算 `isReplacing`。
- **任务契约只走 `ConfigRepository`**:`startReplace` 用 `enqueueUniqueWork(KEEP)` 保证单任务;`cancelReplace` 同时取消 WorkManager 并置 CANCELLED;`dismissReplaceResult` 只重置状态、不取消任务。
- **目标包名禁止回退**:唯一来源是 `PreferencesManager.appPackageName` → `ReplacingState.targetPackage`。空包名/非法包名 fail-closed 直接失败,**不允许任何默认包名兜底**。
- **目标路径唯一映射入口**:`PathConstants.resolveTargetFile()`。目标必须落在 `/storage/emulated/0/Android/(data|obb)/<targetPackage>/` 下,经 `isSafeTargetPath` 校验;shell 参数必须 `shellEscape`。
- **压缩包先校验后写入**:唯一校验器是 `core/security/ArchiveEntryValidator`(拒绝 `..`、绝对路径、盘符、NUL 等)。所有解压路径(`ExtractManager`、`UniversalExtractor`、`ArchiveManager`)必须先完整校验条目再写入。RAR 等不支持格式 fail-closed,不留半成品。
- **复制完成后强制验证**:三个 Orchestrator 都必须跑 `VerificationManager.verify`,`verifiedCount != totalFiles` 即任务失败。

## 模块与依赖方向

```
:app → :data → :domain ← :core
```

| 模块 | 类型 | 职责 |
|------|------|------|
| `:app` | Android application | UI 层:Activity、Compose、MVI ViewModel |
| `:core` | Android library | 基础设施:Shizuku、Worker/Orchestrator、Manager、Performance、Utils、**AIDL(唯一归属)** |
| `:domain` | 纯 Kotlin JVM 模块 | 业务实体、Repository 接口、UseCase;**禁止依赖 Android** |
| `:data` | Android library | Repository 实现、PreferencesManager、ReplaceHistoryManager |

## 常用命令

Windows 环境,用 `gradlew.bat`:

```powershell
# 构建
.\gradlew.bat assembleDebug

# 全部单元测试(分模块)
.\gradlew.bat testDebugUnitTest   # :app 与 :core
.\gradlew.bat :domain:test        # 纯 JVM 模块

# 代码质量门禁(Detekt + Ktlint + Jacoco 覆盖率验证,注册于 :app)
.\gradlew.bat checkQuality

# 自动格式化
.\gradlew.bat formatCode

# 覆盖率报告(app/build/reports/jacoco)
.\gradlew.bat jacocoTestReport
```

覆盖率门槛(注册于 `:app` 的 `jacocoTestCoverageVerification`):行覆盖 ≥ 80%,分支覆盖 ≥ 70%。

## 技术栈

- Kotlin 2.0.21 / AGP 8.7.3 / JVM target 11 / minSdk 26 / target & compileSdk 36(版本以 `gradle/libs.versions.toml` 为准)
- UI:**Compose(Material 3)+ ViewBinding/DataBinding 混合**;组件按 atoms/molecules/organisms 分层(`ui/components/` 与 `ui/compose/`)
- 架构:MVI(`ui/mvi/` 的 Intent/State/ViewModel)+ Factory 手动依赖注入(**无 Hilt/Koin**)
- 异步:Coroutines + Flow;后台任务:WorkManager(`FileReplaceWorkerV2` + Orchestrator 模式)
- 特权操作:Shizuku 13(dev.rikka.shizuku,通过 AIDL Binder 传输文件流)
- 归档:Zip4j(zip)+ commons-compress/xz(7z)
- 依赖仓库走阿里云镜像 + jitpack(见 `settings.gradle.kts`,国内网络)

## 代码约定

- 注释、文档、提交信息用简体中文;标识符英文
- 状态用 sealed class 建模,`when` 必须穷尽、不写 `else`
- 不可变优先:`val` + `data class` + `state.copy()`
- 禁止 `!!`;用 `?.` / `?:` / `requireNotNull`
- 复制策略实现 `CopyStrategy` 接口,编排逻辑在 `worker/orchestrator/`(Root/Shizuku/Normal 三个 Orchestrator)
- 测试命名用反引号中文/英文描述句;测试框架 JUnit4 + MockK + Turbine + coroutines-test + Robolectric;**优先手写 Fake 而非 Mock**
- Detekt 配置:`config/detekt/detekt.yml`(含 baseline `config/detekt/baseline.xml`)

## 边界

- `graft/` 目录是代码知识图谱的自动生成产物,**不要手工编辑**
- `app/build/`、`.gradle/`、`graft/` 均为生成物
- README.md 的版本号(3.1.0)已过时,以 `app/build.gradle.kts` 的 versionName 为准
