# ARCHITECTURE.md

## 分层架构

本项目采用四层结构：

```text
controller -> application -> domain -> infrastructure
```

## 各层职责

### controller

- 接收 HTTP 请求。
- 做基础参数校验。
- 调用 application service。
- 返回统一响应。
- 不得直接访问 repository。

### application

- 编排业务用例。
- 定义事务边界。
- 调用 domain 模型。
- 调用 infrastructure port。

### domain

- 表达核心业务概念。
- 维护领域状态和业务规则。
- 不依赖 Spring、数据库、消息队列、HTTP 框架。

### infrastructure

- 实现数据库访问。
- 实现 Redis、Kafka、外部支付平台适配。
- 实现 repository adapter。

## 订单状态

- `PENDING_PAYMENT`：待支付。
- `PAID`：已支付。
- `CANCELLED`：已取消。

## 关键业务规则

- 创建订单时必须生成价格快照。
- 库存不足时不得创建订单。
- 支付回调必须根据 `transactionId` 幂等。
- 重复支付回调不得重复扣减库存。
- 订单支付成功后必须发布 `OrderPaidEvent`。