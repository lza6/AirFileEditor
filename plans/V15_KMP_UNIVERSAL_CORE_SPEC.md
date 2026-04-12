# V15.0.0 跨平台核心 (KMP) 与通用 IO 调度引擎

## 1. 战略定位
将本项目从 Android 专有工具，升级为基于 Kotlin Multiplatform 的全平台文件管理核心库。

## 2. 技术栈重构
- **语言**: 全量迁移到 Kotlin (100% expect/actual)。
- **IO 抽象**:
    - `androidMain`: 使用 Shizuku/Root/JavaNIO。
    - `iosMain`: 使用 C-Interop 访问底层文件系统。
    - `desktopMain`: 使用标准 Java NIO.2。

## 3. 分发计划
- 发布到 Maven Central。
- 提供 `:core-io` 库供其他开发者集成。
- 该库将包含我们引以为傲的“大文件抽样哈希”和“并发文件流调度”算法。
