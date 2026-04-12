# 项目垃圾清理与陈旧代码审计清单

## 1. 立即删除清单 (Immediate Deletion)
这些文件属于旧架构残留，且已有 V2 版本替代，保留它们会增加后续 AI 理解代码的噪音。

- **`app/src/main/java/com/example/tfgwj/worker/FileReplaceWorker.kt`**: 
    - **理由**: 这是 V1 版本的同步 Worker，代码逻辑混乱且无并发控制。
    - **替代者**: `FileReplaceWorkerV2.kt`。
- **`app/src/main/java/com/example/tfgwj/shizuku/FileOperationService.kt` 中的旧方法**: 
    - **理由**: 存在大量通过 `String` 传递路径的同步阻塞方法。
    - **清理建议**: 仅保留支持 `ParcelFileDescriptor` 的异步流式方法。
- **`app/src/main/res/layout/` 中的过时 XML**: 
    - **建议**: 在 V11 迁移 Compose 前，标记所有非核心 UI 的 XML 为 `Deprecated` 并在后续版本中物理删除。
- **项目根目录下的临时文件**:
    - `c.txt` (之前测试留下的)
    - `config/detekt/detekt.yml.bak` (备份文件)

## 2. 代码中的“坏味道”清理指南
建议后续 AI 在修改代码时执行以下操作：

- **移除 Hardcoded Paths**: 
    - 检查 `MainActivity` 和 `PatchManager`，将所有类似 `"/sdcard/Android/data"` 的字符串替换为 `PathConstants` 中的引用。
- **消除空 catch 块**: 
    - `UniversalExtractor.kt` 和 `UpdateManager.kt` 中存在多处 `try { ... } catch(e: Exception) {}`。
    - **改进**: 必须添加 `AppLogger.e` 记录，或通过 `Result<T>` 封装。
- **统一资源关闭**:
    - 确保所有 `FileInputStream` 均使用 `.use { ... }` 扩展函数，严禁手动 `close()` 以防内存泄漏。

## 3. 清理脚本建议
如果您有权限，可以运行以下命令进行物理清理：
```bash
rm app/src/main/java/com/example/tfgwj/worker/FileReplaceWorker.kt
rm c.txt
rm config/detekt/detekt.yml.bak
```
