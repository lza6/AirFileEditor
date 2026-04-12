# MainActivity 重构与“屎山”清理指南

## 1. 现状评估
`MainActivity.kt` 包含：
- UI 初始化 (ViewBinding/DataBinding)
- WorkManager 状态监听
- 大量 Dialog 构建逻辑
- 权限申请逻辑
- 压缩包解析中转
- 文件路径处理 (URI to Path)

## 2. 拆解指南 (分 5 步走)

### 第一步：提取 ViewModel (State Management)
- **目标**: 将所有 `var`, `LiveData`, `Flow` 迁移到 `MainViewModel`。
- **职责**: 管理进度百分比、错误列表、当前执行的 WorkId。

### 第二步：提取权限协调器 (PermissionCoordinator)
- **目标**: 消除 Activity 中的 `requestPermission` 回调。
- **实现**: 创建一个不带界面的 Fragment 或使用 ActivityResultRegistry，集中处理存储权限、Root 权限、Shizuku 权限。

### 第三步：Dialog 组件化
- **目标**: 将 `showReplaceProgressDialog`, `showModeSelectionDialog` 等私有方法提取为独立的 `DialogFragment` 或 `Compose Modal`。
- **好处**: 提升代码复用率，减少 Activity 行数。

### 第四步：文件操作路由 (FileOperationRouter)
- **目标**: 处理 URI 转换和路径校验逻辑。
- **方法**: 提取 `UriUtils` 和 `PathNavigator` 模块，将 `getPathFromUri` 等逻辑移出。

### 第五步：UI 与 业务逻辑 解耦
- **目标**: Activity 仅作为观察者，不再参与逻辑判断。

## 3. 垃圾清理清单 (Old Product Cleanup)
- [ ] 彻底删除 `FileReplaceWorker` (V1 版本)，全面迁移到 `FileReplaceWorkerV2`。
- [ ] 删除 `NormalCopyStrategy` 中过时的 `FileInputStream.read` 循环，统一使用 `IoOptimizer`。
- [ ] 清理 `MainActivity` 中注释掉的过时 UI 代码。
- [ ] 移除 `project_specs.md` 中标为“已弃用”的 V2 - V5 兼容性说明。

## 4. 关键原则
- **保持单一职责**: 每一个类不应超过 400 行。
- **依赖倒置**: Activity 依赖接口，而非具体实现。
- **防御性编程**: 对所有文件路径操作进行 Null-Safe 检查。
