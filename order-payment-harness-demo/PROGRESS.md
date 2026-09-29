# PROGRESS.md

## 当前目标

从零搭建订单支付场景的 Harness，并逐步实现订单创建与支付确认链路。

## 已完成

- 创建项目目录。
- 初始化 Git。
- 创建 Harness 基础目录。
- 创建 AGENTS.md。
- 创建 ARCHITECTURE.md。
- 创建业务需求文档。
- 创建功能清单。
- 创建执行计划。
- 初始化 Java Spring Boot 项目。
- 创建 Order。
- 创建 OrderItem。
- 创建 OrderStatus。
- 创建 PaymentTransaction。
- 为 Order 增加 markPaid() 领域方法。
- 增加领域单元测试。
- 创建 orders 表 Flyway migration。
- 创建 order_items 表 Flyway migration。
- 创建 payment_transactions 表 Flyway migration。
- 增加必要索引和 transaction_id 唯一约束。
- 实现创建订单用例。
- 暴露创建订单 API。
- 实现支付成功回调。

## 进行中

- Step 5：收尾与后续增强。

## 待完成

- 暂无。

## 阻塞项

- 暂无。

## 交接说明

- 当前已完成订单创建与支付回调两条主链路。
- 后续可继续做支付侧的真实适配、事件外发和更完整的异常治理。

## 验证记录

- 2026-06-29：执行 `./gradlew test`，通过。
- 2026-06-29：复核 Step 0，执行 `./gradlew test`，通过。
- 2026-06-29：完成 Step 1，执行 `./gradlew test`，通过。
- 2026-06-29：完成 Step 2，执行 `./gradlew integrationTest`，通过。
- 2026-07-01：完成 F001，执行 `./gradlew test`，通过。
- 2026-07-01：完成 F001，执行 `./gradlew integrationTest`，通过。
- 2026-07-01：完成 F002，执行 `./gradlew test`，通过。
- 2026-07-01：完成 F002，执行 `./gradlew integrationTest`，通过。
- 2026-07-01：完成 F002，执行 `./gradlew clean check`，通过。
