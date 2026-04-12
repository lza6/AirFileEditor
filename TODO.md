# V9.0.0 质量保证基石 - 剩余执行计划

基于项目画像，V9 完成度 95%，剩余 3 核心步骤：

## ✅ 已完成 (从文件分析)
- 测试基础设施 (Jacoco/Detekt/Ktlint 配置)
- 18+ 单元/集成测试 (~10k 行，核心模块覆盖)

## ⏳ 待执行步骤 (优先级顺序)

### 1. 创建 GitHub Actions CI (Task 9.20，高优先)
- 创建 `.github/workflows/ci.yml`
- 自动化：test + quality + build
- Codecov 覆盖率上传
- 质量门禁：80% 覆盖 / Detekt 零严重

### 2. 运行全量测试验证 (Task 9.21，中优先)
```
./gradlew testDebugUnitTest
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
./gradlew detekt
./gradlew ktlintCheck
./gradlew checkQuality
```
- 确认所有测试通过
- 生成 HTML 覆盖率报告 (app/build/reports/jacoco/)

### 3. 生成覆盖率报告 + V9 验收 (Task 9.22，低优先)
- 验证核心模块 >80% 覆盖
- 更新 project_specs.md (V9 ✅)
- 输出完成报告

## 📈 预期效果
- CI/CD 全自动化
- 质量门禁上线
- V9.0.0 正式发布准备就绪

## Follow-up
- 完成 V9 后：MainActivity 重构 (V11 Compose)
- 长期：V10 APM + ProGuard (体积优化)

