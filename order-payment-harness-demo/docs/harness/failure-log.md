# Harness Failure Log

## 日期

### 任务

F001 创建订单 API。

### 失败现象

Agent 创建了 Controller，但直接调用 Repository，违反架构规则。

### 归因

架构约束没有自动化测试。

### 修复动作

- 在 ARCHITECTURE.md 强化 Controller 不得访问 Repository。
- 增加 ArchUnit 测试。
- 在 AGENTS.md 中加入该硬约束。

### 防复发验证

- ./gradlew test
- ./gradlew archTest
