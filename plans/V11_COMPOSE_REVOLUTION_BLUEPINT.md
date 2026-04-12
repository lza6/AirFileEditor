# V11.0.0 Compose UI 革命重构蓝图

## 1. 核心目标
将传统的 `View-based` UI 彻底迁移到 `Jetpack Compose`，实现代码量减少 50%，流畅度提升 30%。

## 2. 状态驱动架构 (MVI 模式推荐)
- **Intent**: 用户点击替换按钮。
- **State**: `ReplacingState(progress, speed, currentFile, isPaused)`.
- **View**: Compose 响应 `State` 自动重绘。

## 3. UI 组件拆解清单 (Atomic Design)

### Atoms (原子组件)
- `StatusBadge`: 显示任务状态 (成功/失败/进行中)。
- `IoSpeedText`: 格式化后的速度显示（带有单位自动转换）。

### Molecules (分子组件)
- `FileProgressCard`: 包含进度条、文件名、取消按钮的独立组件。
- `ModeSelectorGroup`: Root/Shizuku 切换的选择组。

### Organisms (生物组件)
- `MainDashboard`: 首页核心看板。
- `LogConsole`: 实时滚动日志展示区。

## 4. 动画方案
- **Transition Animation**: 进入和退出替换界面的缩放效果。
- **Lottie Integration**: 任务完成后的礼花喷洒效果。

## 5. 迁移策略 (Hybrid Approach)
1. **第一阶段**: 保持 `MainActivity` 基础容器，先用 `ComposeView` 替换首页的核心 Card。
2. **第二阶段**: 将所有 Dialog 替换为 `Compose ModalBottomSheet`。
3. **第三阶段**: 彻底移除 `R.layout.activity_main`。
