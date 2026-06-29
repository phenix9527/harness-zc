# 订单创建与支付确认业务需求

## 背景

用户在电商系统中提交订单。系统需要校验商品和库存，创建待支付订单。支付平台回调成功后，系统确认订单已支付，并发布订单已支付事件。

## 用户故事

作为用户，我希望提交订单后能获得一个待支付订单，以便继续完成支付。

作为系统，我希望支付成功回调可以幂等处理，以避免重复扣减库存或重复发布事件。

## 功能一：创建订单

### 输入

- userId
- items
    - skuId
    - quantity

### 输出

- orderId
- orderStatus
- payableAmount
- paymentToken

### 规则

- 商品不存在时返回错误。
- 商品不可售时返回错误。
- 库存不足时返回错误。
- 创建成功后订单状态为 `PENDING_PAYMENT`。

## 功能二：支付成功回调

### 输入

- orderId
- transactionId
- paidAmount
- signature

### 输出

- callback handled result

### 规则

- 签名错误返回 401。
- 订单不存在返回错误。
- 重复 transactionId 只能处理一次。
- 支付成功后订单状态变为 `PAID`。
- 成功后发布 `OrderPaidEvent`。