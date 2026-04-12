# V13.0.0 隐匿、对抗与 Phantom Stealth 2.0 规格书

## 1. 对抗目标
规避应用商店、加固系统、反作弊引擎对 Root 行为及文件修改痕迹的扫描。

## 2. 核心技术点

### 2.1 路径混淆 (Path Obfuscation)
- **方案**: 在替换 `/data/data` 目录时，临时挂载一个虚拟路径或通过 `Inotify` 监控，在文件访问瞬间进行动态重定向。
- **目标**: 让目标 App 认为自己访问的是原始文件。

### 2.2 伪造时间指纹 (Temporal Fingerprint Masking)
- **增强**: `FileTimeModifier` 升级为 `TemporalSignatureManager`。
- **逻辑**: 自动爬取系统中正常 App 的文件修改分布规律，生成一个符合正态分布的随机修改时间序列，而不是机械的统一时间。

### 2.3 Shizuku 进程混淆
- **逻辑**: 修改 AIDL 服务名称，在通讯过程中使用动态加密，防止被特征库扫描。

## 3. 风险预警
- 对抗技术可能导致系统稳定性下降。
- 必须配合 V10 提到的 `IntegrityVerifier` 确保文件数据万无一失。
