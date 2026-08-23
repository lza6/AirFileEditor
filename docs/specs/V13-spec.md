# V13 Spec：安全与任务闭环

## 问题与背景

V12 完成了四模块拆分，但任务生命周期、目标包名、压缩包解压、特权 IPC 仍可能分叉。未提交 diff 已朝 fail-closed 收敛，必须冻结成可回归基线。

## 用户

- 需要替换 `Android/data|obb/<package>` 的玩家/折腾用户
- 后续接手的开发者/其他 AI

## 目标

1. TaskPhase 是唯一任务状态源，终态可关闭 Overlay
2. 空/非法包名直接失败，无默认包名
3. 所有解压入口先校验后写入；炸弹、重复条目、失败残留 fail-closed
4. AIDL 命令/路径白名单
5. 构建可复现：compileSdk 对齐，无死 Service 声明

## 非目标

- V14 性能引擎 / 全 Compose
- KMP / 端侧 AI
- 服务端高并发基础设施

## 验收标准

- `.\gradlew.bat assembleDebug` 成功
- 状态机、路径、解压安全、AIDL、ViewModel 定向单测通过
- 开始替换在空包名时禁用；取消后 Worker 不再写入（代码路径）
- RAR 不写入半成品

## 已知限制

- 无真机/模拟器，设备 E2E 未验证
- Robolectric 全量 `:core:testDebugUnitTest` 曾卡死，不作为本轮门禁
