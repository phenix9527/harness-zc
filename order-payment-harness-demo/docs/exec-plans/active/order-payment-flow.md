# Exec Plan: Order Payment Flow

## 当前原则

- 每次只做一个可验证切片。
- 先实现 F001，再实现 F002。
- 每一步完成后必须运行对应验证命令。
- 每一步完成后必须更新 PROGRESS.md。

## Step 0: 初始化 Spring Boot 项目

目标：
- 创建基础 Gradle Spring Boot 项目。
- 配置 JDK 21。
- 添加 Web、Validation、Data JPA、Flyway、PostgreSQL、Testcontainers、ArchUnit 依赖。

验证：
- ./gradlew test

## Step 1: 创建领域模型

目标：
- 创建 Order。
- 创建 OrderItem。
- 创建 OrderStatus。
- 创建 PaymentTransaction。

验证：
- ./gradlew test

## Step 2: 创建数据库 migration

目标：
- 创建 orders 表。
- 创建 order_items 表。
- 创建 payment_transactions 表。

验证：
- ./gradlew integrationTest

## Step 3: 实现创建订单用例

目标：
- 创建 CreateOrderCommand。
- 创建 CreateOrderService。
- 创建 OrderRepository port。
- 创建库存检查 port。

验证：
- ./gradlew test
- ./gradlew integrationTest

## Step 4: 暴露创建订单 API

目标：
- 创建 OrderController。
- 创建请求 DTO。
- 创建响应 DTO。
- 创建错误码映射。

验证：
- ./gradlew integrationTest

## Step 5: 实现支付回调

目标：
- 创建 PaymentCallbackController。
- 创建 PaymentCallbackService。
- 实现 transactionId 幂等。
- 发布 OrderPaidEvent。

验证：
- ./gradlew clean check