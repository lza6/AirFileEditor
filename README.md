# 听风改文件

> 一款专为和平精英游戏打造的文件管理工具，支持智能增量更新、文件时间修改、压缩包解压等功能。

![Android](https://img.shields.io/badge/Android-5.0+-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

## ✨ 主要功能

### 🎯 文件替换
- **智能增量更新** - 自动检测文件内容变化，跳过相同文件，大幅提升替换速度（二次替换提升 80%-95%）
- **Shizuku 高性能模式** - 利用 Shizuku 根权限实现快速批量文件复制
- **进度实时显示** - 实时进度条、速度显示、预估剩余时间
- **错误处理** - 失败文件列表展示，方便排查问题

### 📦 压缩包管理
- **多格式支持** - 支持 ZIP、7z 格式压缩包
- **智能解压** - 根据文件大小自适应缓冲区，提升解压性能
- **版本管理** - 自动识别压缩包版本，避免重复解压
- **密码支持** - 自动识别加密压缩包，支持密码输入

### 🕒 文件时间管理
- **一键随机时间** - 随机生成 2027-2029 年的文件时间
- **自定义时间** - 支持手动选择任意时间
- **时间锁定** - 锁定好用的时间，下次直接应用
- **时间应用** - 将锁定的时间应用到所有文件

### 📋 小包管理
- **版本扫描** - 自动扫描缓存目录中的小包
- **快速应用** - 一键将小包文件应用到主包
- **增量复制** - 智能跳过相同文件，提升更新速度

### 🧹 环境清理
- **选择性清理** - 保留核心数据（Paks、PandoraV2、ImageDownloadV3）
- **安全清理** - 清理其他配置文件，重置游戏环境

## 🚀 技术特性

### 核心优化
- **协程并发控制** - 使用 Semaphore 控制并发数，避免 OOM
- **自适应缓冲区** - 根据文件大小动态调整缓冲区（64KB ~ 1MB）
- **哈希比对** - MD5 哈希精确校验，确保文件一致性
- **内存优化** - Channel 限制日志队列，批量写入减少 I/O

### 权限管理
- **Shizuku 支持** - 利用 Shizuku 获取高性能文件操作权限
- **存储管理** - 支持 Android 11+ 存储权限管理
- **权限检测** - 自动检测并提示用户授予权限

### 性能优化
- **单线程解压** - 稳定的单线程解压模式，兼容性更好
- **流式处理** - 大文件分块处理，避免内存溢出
- **低内存警告** - 自动响应系统内存警告，及时释放资源

## 📱 系统要求

- **Android 版本**: 5.0 (API 26) 及以上
- **存储空间**: 至少 2GB 可用空间
- **权限**:
  - 存储权限（访问外部存储）
  - Shizuku 权限（高性能文件操作）
  - 网络权限（可选，用于下载更新）

## 🔧 开发环境

```kotlin
- Kotlin: 2.1.0
- Gradle: 8.1.0
- Android Gradle Plugin: 8.1.0
- Compile SDK: 36
- Min SDK: 26
- Target SDK: 36
```

## 📦 主要依赖

```kotlin
// 协程
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// 文件压缩
implementation("org.apache.commons:commons-compress:1.26.0")
implementation("net.lingala.zip4j:zip4j:2.11.5")

// Shizuku
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")

// WorkManager
implementation("androidx.work:work-runtime-ktx:2.9.0")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.0.0")

// Material Design
implementation("com.google.android.material:material:1.12.0")
```

## 🛠️ 构建说明

### 前置要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 11 或更高版本
- Android SDK 36

### 构建步骤

1. 克隆项目
```bash
git clone https://github.com/lza6/AirFileEditor.git
cd AirFileEditor
```

2. 在 Android Studio 中打开项目

3. 等待 Gradle 同步完成

4. 构建项目
```
Build → Make Project
```

5. 生成 APK
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```



## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📞 联系方式

- **TG客服**: @TFGY999
- **微信**: Tf00798
- **QQ**: 3353620663

## ⚠️ 免责声明

本工具仅供学习和研究使用，请勿用于非法用途。使用本工具所产生的一切后果由使用者自行承担。

---

**听风改文件** - 让文件管理更简单、更高效！
