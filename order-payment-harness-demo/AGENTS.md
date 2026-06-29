# AGENTS.md

## 项目概览

这是一个 Java Spring Boot 订单支付示例项目，用于演示 Harness Engineering 落地。

业务目标：
- 用户创建订单。
- 系统锁定库存。
- 用户支付成功后，系统确认订单已支付。
- 系统发布订单已支付事件。

技术栈：
- JDK 21
- Spring Boot 3.x
- Gradle
- PostgreSQL
- Redis
- Kafka
- Flyway
- JUnit 5
- Testcontainers
- ArchUnit

## 必读文件

开始任何任务前，必须阅读：

- `AGENTS.md`
- `ARCHITECTURE.md`
- `PROGRESS.md`
- `docs/product-specs/order-payment-flow.md`
- `docs/exec-plans/active/order-payment-flow.md`
- `docs/harness/definition-of-done.md`

## 硬约束

- Controller 不得直接访问 Repository。
- Domain 层不得依赖 Spring Web、JPA、Kafka、Redis。
- Application 层负责事务边界和用例编排。
- 支付回调必须幂等。
- 数据库结构变更必须通过 Flyway migration。
- 不得为了让测试通过而删除测试或降低业务校验。
- 未运行验证命令，不得声明任务完成。

## 标准验证命令

如果是 Gradle 项目：

```bash
./gradlew test
./gradlew integrationTest
./gradlew clean check
```

如果项目还没有初始化 Gradle，先只维护文档和计划，不要假装验证已经通过。

## 完成前必须做

- 说明完成了什么。
- 说明没有完成什么。
- 说明实际运行了哪些验证命令。
- 更新 `PROGRESS.md`。