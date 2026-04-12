# V10.0.0 极致性能与 APM (应用性能监控) 详细设计说明书

## 1. APM (Application Performance Monitoring) 体系

### 1.1 实时监控指标 (Metrics)
我们需要在 `PerformanceMonitor` 中建立以下核心指标的采集：
- **IO Wait**: 每次文件写入时的硬件等待延迟。
- **IPC Latency**: Shizuku 模式下 Binder 跨进程通讯的时延。
- **Throughput Curve**: 实时吞吐量曲线（Byte/sec）。
- **Failure Analysis**: 失败操作的热点目录（定位分区存储权限最严格的包名）。

### 1.2 数据可视化预备
- 在 V10 中引入 `PerformanceDashboardActivity` (虽然以后会是 Compose)，用于展示实时的 IO 仪表盘。

## 2. IO 极致提速方案 (NIO 2.0)

### 2.1 批次聚合 (Batch Consolidation)
- **痛点**: 大量 1KB 以下的小文件（如 .xml, .json）复制非常缓慢。
- **设计**: 在 `NormalCopyOrchestrator` 逻辑中，增加一个小文件缓冲区。将 100 个以上的小文件操作合并为一个大的 `FileChannel` 传输序列。

### 2.2 Unix Domain Socket (UDS) 预研
- **针对 Shizuku**: 如果文件数量巨大，Binder 序列化将成为瓶颈。
- **设计**: 尝试在 `FileOperationService` 中启动一个临时 UDS 服务端，由主进程直接通过 Socket 传输流，绕过 Parcelable 的限制。

## 3. 并发模型改进
- **动态 Permit 调度**: 目前 `CopyConfig` 的 Permit 是固定的。
- **升级**: 引入 `AdaptivePermitScheduler`。当检测到系统 CPU 负载 > 80% 或内存剩余 < 100MB 时，动态降低并发线程数，防止 UI 掉帧或系统 OOM。
