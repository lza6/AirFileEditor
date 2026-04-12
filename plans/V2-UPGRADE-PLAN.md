# 听风改文件 V2 升级计划

## 项目当前状态
- 版本: V3.1.0 (构建) / 路线图 V9.0.0 质量保证阶段
- 架构: V8.0.0 Orchestrator架构已完成
- 网络层: V7.0.0 OkHttp升级 + 断点续传
- IO优化: V6.0.0 mmap极速映射

## 升级任务清单

### Phase 1: 代码质量与测试完善
- [ ] 1. 运行现有测试确保通过，修复失败测试
- [ ] 2. 检查Jacoco覆盖率报告，核心模块目标>80%
- [ ] 3. 执行Detekt静态分析，修复严重问题
- [ ] 4. 执行Ktlint代码格式化
- [ ] 5. 运行质量门禁 checkQuality 任务

### Phase 2: 性能优化增强
- [ ] 6. 分析PerformanceBenchmarkTest结果，验证V2比V1性能提升
- [ ] 7. 优化IoOptimizer mmap分片策略(当前8MB可调整)
- [ ] 8. 增强ProgressTracker速度计算精度
- [ ] 9. 优化StorageTypeDetector检测逻辑

### Phase 3: 功能完善与增强
- [ ] 10. 增强RuleEngine规则缓存机制(支持更多镜像)
- [ ] 11. 增强UpdateManager断点续传稳定性
- [ ] 12. 完善AppInstaller安装失败重试逻辑
- [ ] 13. 增强StealthManager隐匿功能

### Phase 4: 架构优化与代码清理
- [ ] 14. 清理未使用的旧代码和重复实现
- [ ] 15. 优化MainActivity代码结构(过长3171行)
- [ ] 16. 统一日志输出格式(AppLogger)
- [ ] 17. 完善异常处理和错误信息

### Phase 5: 用户体验提升
- [ ] 18. 优化进度显示界面(实时速度、ETA预测)
- [ ] 19. 增强错误提示信息可读性
- [ ] 20. 优化日志显示滚动性能

### Phase 6: 文档与配置更新
- [ ] 21. 更新 project_specs.md 版本状态
- [ ] 22. 更新 db_structure.md (如有变化)
- [ ] 23. 更新 README.md (如有新功能)
- [ ] 24. 清理 plans/ 目录旧计划文件

## 执行顺序
优先执行Phase 1，确保代码质量和测试通过后再进行功能增强。