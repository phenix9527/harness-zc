# Harness 从零落地操作手册：订单支付场景

> 这份文档回答一个最实际的问题：从一个空白文件夹开始，如何一步一步搭建 Harness，并让 AI Agent 按规则完成一个 Java 业务功能。

## 适用对象

- Java 架构师
- Tech Lead
- 后端开发团队
- 希望把 AI Agent 引入真实项目交付的团队

## 最终目标

从空白目录开始，搭建一个可复用的 Harness 工作环境，然后让 Agent 按步骤完成“订单创建与支付确认”业务链路。

本文不要求一开始就写完整代码。第一目标是先把 Agent 能稳定工作的工程轨道铺好。

## 总览：你要做哪几件事

整体落地顺序如下：

1. 创建空白项目目录。
2. 初始化 Git 仓库。
3. 创建 Harness 基础目录。
4. 创建 `AGENTS.md`，告诉 Agent 项目规则。
5. 创建 `PROGRESS.md`，记录当前进度。
6. 创建 `ARCHITECTURE.md`，写清架构边界。
7. 创建需求和功能清单。
8. 创建执行计划。
9. 创建验证命令入口。
10. 让 Agent 初始化读取上下文。
11. 让 Agent 执行第一个小功能。
12. 运行验证并修补 Harness。
13. 会话结束前交接。

## 第 1 步：创建空白文件夹

在你的工作目录下创建一个新项目目录。

```bash
mkdir order-payment-harness-demo
cd order-payment-harness-demo
```

如果你在 Windows PowerShell：

```powershell
New-Item -ItemType Directory -Path order-payment-harness-demo
Set-Location order-payment-harness-demo
```

此时目录是空的。

## 第 2 步：初始化 Git

```bash
git init
```

创建 `.gitignore`。

```bash
touch .gitignore
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path .gitignore
```

`.gitignore` 建议内容：

```gitignore
.gradle/
build/
out/
.idea/
*.iml
*.log
.env
```

为什么这一步重要：

- Git 是 Agent 的状态边界。
- 后续 Agent 修改了什么，你可以用 `git diff` 查看。
- 任务做到一半失败，也能更容易回退或比较。

## 第 3 步：创建 Harness 目录结构

先不要急着写业务代码，先创建 Harness 文件结构。

```bash
mkdir -p docs/harness
mkdir -p docs/product-specs
mkdir -p docs/exec-plans/active
mkdir -p docs/exec-plans/completed
```

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force -Path docs/harness
New-Item -ItemType Directory -Force -Path docs/product-specs
New-Item -ItemType Directory -Force -Path docs/exec-plans/active
New-Item -ItemType Directory -Force -Path docs/exec-plans/completed
```

最终目录应类似：

```text
order-payment-harness-demo/
├── docs/
│   ├── harness/
│   ├── product-specs/
│   └── exec-plans/
│       ├── active/
│       └── completed/
└── .gitignore
```

## 第 4 步：创建 `AGENTS.md`

`AGENTS.md` 是 Agent 的入口文件。每次 Agent 开始工作，都应该先读它。

在项目根目录创建：

```bash
touch AGENTS.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path AGENTS.md
```

写入以下内容：

```markdown
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
```

完成后，你已经有了 Agent 的“入口说明书”。

## 第 5 步：创建 `PROGRESS.md`

`PROGRESS.md` 用来解决跨会话断片问题。

创建文件：

```bash
touch PROGRESS.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path PROGRESS.md
```

写入：

```markdown
# PROGRESS.md

## 当前目标

从零搭建订单支付场景的 Harness，并逐步实现订单创建与支付确认链路。

## 已完成

- 创建项目目录。
- 初始化 Git。
- 创建 Harness 基础目录。
- 创建 AGENTS.md。

## 进行中

- 创建 Harness 基础文档。

## 待完成

- 创建 ARCHITECTURE.md。
- 创建业务需求文档。
- 创建功能清单。
- 创建执行计划。
- 初始化 Java Spring Boot 项目。
- 实现订单创建功能。
- 实现支付回调功能。

## 阻塞项

- 暂无。

## 验证记录

- 暂无代码验证。
```

以后每次 Agent 工作结束前，都必须更新这个文件。

## 第 6 步：创建 `ARCHITECTURE.md`

`ARCHITECTURE.md` 负责告诉 Agent：代码应该怎么组织，哪些边界不能越过。

创建：

```bash
touch ARCHITECTURE.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path ARCHITECTURE.md
```

写入：

```markdown
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
```

这一步完成后，Agent 不需要猜你的架构。

## 第 7 步：创建完成定义

创建：

```bash
touch docs/harness/definition-of-done.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path docs/harness/definition-of-done.md
```

写入：

```markdown
# Definition of Done

一个任务只有同时满足以下条件，才能声明完成：

- 实现内容没有超出任务 scope。
- 没有实现 out_of_scope 中明确排除的内容。
- 新增或修改的业务逻辑有测试覆盖。
- 数据库结构变更通过 Flyway migration。
- 错误场景有明确错误码或异常处理。
- 涉及外部调用时有超时、失败处理或幂等设计。
- 已运行任务要求的验证命令。
- `PROGRESS.md` 已更新。

如果验证命令无法运行，必须明确说明原因，不能声称验证通过。
```

## 第 8 步：创建业务需求文档

创建：

```bash
touch docs/product-specs/order-payment-flow.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path docs/product-specs/order-payment-flow.md
```

写入：

```markdown
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
```

## 第 9 步：创建功能清单

创建：

```bash
touch docs/harness/feature-list-order-payment.json
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path docs/harness/feature-list-order-payment.json
```

写入：

```json
{
  "epic": "订单创建与支付确认链路",
  "features": [
    {
      "id": "F001",
      "name": "创建待支付订单",
      "scope": [
        "新增 POST /api/v1/orders",
        "校验商品存在且可售",
        "校验库存是否充足",
        "创建 PENDING_PAYMENT 订单",
        "返回 orderId、orderStatus、payableAmount、paymentToken"
      ],
      "out_of_scope": [
        "不实现支付回调",
        "不接入真实支付平台",
        "不实现优惠券",
        "不实现退款"
      ],
      "acceptance_criteria": [
        "创建成功返回 PENDING_PAYMENT",
        "库存不足返回明确错误",
        "非法商品返回明确错误",
        "包含单元测试",
        "包含 API 集成测试"
      ],
      "verification": [
        "./gradlew test",
        "./gradlew integrationTest"
      ]
    },
    {
      "id": "F002",
      "name": "支付成功回调",
      "scope": [
        "新增 POST /api/v1/payments/callback",
        "校验支付签名",
        "根据 transactionId 幂等处理",
        "订单状态变更为 PAID",
        "发布 OrderPaidEvent"
      ],
      "out_of_scope": [
        "不实现退款",
        "不实现分账",
        "不实现支付超时关闭"
      ],
      "acceptance_criteria": [
        "重复回调只处理一次",
        "签名错误返回 401",
        "订单不存在返回明确错误",
        "OrderPaidEvent 只发布一次"
      ],
      "verification": [
        "./gradlew test",
        "./gradlew integrationTest",
        "./gradlew clean check"
      ]
    }
  ]
}
```

这一步非常关键。它告诉 Agent：什么该做，什么不该做。

## 第 10 步：创建执行计划

创建：

```bash
touch docs/exec-plans/active/order-payment-flow.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path docs/exec-plans/active/order-payment-flow.md
```

写入：

```markdown
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
```

现在，Agent 已经有了可执行路线图。

## 第 11 步：创建验证命令入口

如果项目还没有 Gradle，可以先创建一个说明型 `Makefile`，后续初始化 Java 项目后再让命令真正可运行。

创建：

```bash
touch Makefile
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path Makefile
```

写入：

```makefile
.PHONY: init test integration check

init:
	git status --short

test:
	./gradlew test

integration:
	./gradlew integrationTest

check:
	./gradlew clean check
```

注意：

- 如果当前还没有 `gradlew`，`make test` 会失败，这是正常的。
- 失败结果也有价值，它提醒下一步应该初始化 Spring Boot/Gradle 项目。

## 第 12 步：第一次提交 Harness 基础文件

查看当前文件：

```bash
git status --short
```

提交：

```bash
git add .
git commit -m "chore: initialize harness workspace"
```

为什么要提交：

- 这是 Harness 的稳定基线。
- 后面 Agent 修改业务代码时，你能清楚看到差异。

## 第 13 步：让 Agent 做第一次初始化

现在你可以打开 Codex、Claude Code 或其他 Agent，让它执行初始化。

给 Agent 的提示词：

```text
请先不要修改代码。

请执行初始化：
1. 阅读 AGENTS.md。
2. 阅读 ARCHITECTURE.md。
3. 阅读 PROGRESS.md。
4. 阅读 docs/product-specs/order-payment-flow.md。
5. 阅读 docs/harness/feature-list-order-payment.json。
6. 阅读 docs/exec-plans/active/order-payment-flow.md。
7. 查看 git status。

然后请回答：
- 当前项目目标是什么？
- 当前 Harness 已经具备什么？
- 下一步应该执行哪个 Step？
- 你认为现在是否可以开始写业务代码？
```

期望 Agent 的回答：

- 能说清这是订单支付场景。
- 能说清下一步是初始化 Spring Boot 项目。
- 能指出当前还没有 Gradle 项目，所以不能运行 Java 测试。
- 不应该直接开始乱写业务代码。

如果 Agent 做不到，说明 `AGENTS.md` 或执行计划还不够清晰。

## 第 14 步：让 Agent 初始化 Spring Boot 项目

给 Agent 的提示词：

```text
请执行 docs/exec-plans/active/order-payment-flow.md 中的 Step 0：初始化 Spring Boot 项目。

要求：
- 使用 JDK 21。
- 使用 Gradle。
- 使用 Spring Boot 3.x。
- 添加 Web、Validation、Data JPA、Flyway、PostgreSQL 依赖。
- 添加 JUnit 5、Testcontainers、ArchUnit 测试依赖。
- 创建最小可运行 Spring Boot Application。
- 创建一个空的 contextLoads 测试。
- 不实现订单业务。

完成前：
- 运行 ./gradlew test。
- 更新 PROGRESS.md。
- 汇报实际运行的命令和结果。
```

这一步的重点是创建工程骨架，不做业务。

完成后检查：

```bash
git diff
./gradlew test
```

如果测试通过，提交：

```bash
git add .
git commit -m "chore: initialize spring boot project"
```

## 第 15 步：让 Agent 执行 F001 的第一个小切片

不要一次性让 Agent 实现 F001 全部内容。先让它做领域模型。

提示词：

```text
请执行执行计划 Step 1：创建领域模型。

范围：
- 创建 Order。
- 创建 OrderItem。
- 创建 OrderStatus。
- 创建 PaymentTransaction。
- 为 Order 增加 markPaid() 领域方法。
- 增加领域单元测试。

不做：
- 不创建 Controller。
- 不创建 Repository 实现。
- 不创建数据库 migration。
- 不实现支付回调 API。

必须遵守：
- Domain 层不得依赖 Spring。
- 不得引入 JPA 注解到 Domain 模型中，除非 ARCHITECTURE.md 明确允许。

完成前：
- 运行 ./gradlew test。
- 更新 PROGRESS.md。
```

完成后你检查：

```bash
git diff
./gradlew test
```

如果满意：

```bash
git add .
git commit -m "feat: add order domain model"
```

## 第 16 步：继续执行 F001 的数据库切片

提示词：

```text
请执行执行计划 Step 2：创建数据库 migration。

范围：
- 创建 orders 表。
- 创建 order_items 表。
- 创建 payment_transactions 表。
- 使用 Flyway migration。
- 增加必要索引和唯一约束。

重点：
- payment_transactions.transaction_id 必须唯一。
- orders.order_status 必须能表达 PENDING_PAYMENT、PAID、CANCELLED。

不做：
- 不实现支付回调业务逻辑。
- 不创建 Kafka 事件。

完成前：
- 运行 ./gradlew integrationTest。
- 更新 PROGRESS.md。
```

如果 `integrationTest` 任务还不存在，你要让 Agent 补齐 Gradle 测试任务，而不是跳过验证。

## 第 17 步：继续执行 F001 的应用服务和 API

提示词：

```text
请执行 F001：创建待支付订单 API。

范围：
- 创建 POST /api/v1/orders。
- 创建 CreateOrderCommand。
- 创建 CreateOrderService。
- 创建 OrderRepository port。
- 创建库存检查 port，可以先用测试替身或内存实现。
- 成功后返回 orderId、orderStatus、payableAmount、paymentToken。

不做：
- 不实现支付回调。
- 不接入真实支付平台。
- 不实现优惠券。

验收标准：
- 创建成功返回 PENDING_PAYMENT。
- 库存不足返回明确错误。
- 非法商品返回明确错误。
- 包含单元测试和 API 集成测试。

完成前：
- 运行 ./gradlew test。
- 运行 ./gradlew integrationTest。
- 更新 PROGRESS.md。
```

完成后检查：

```bash
./gradlew test
./gradlew integrationTest
git diff
```

提交：

```bash
git add .
git commit -m "feat: implement create order flow"
```

## 第 18 步：执行 F002 支付回调

提示词：

```text
请执行 F002：支付成功回调。

范围：
- 创建 POST /api/v1/payments/callback。
- 校验支付签名，可以先实现本地可测试的签名校验器。
- 使用 transactionId 做幂等。
- 将订单状态从 PENDING_PAYMENT 变更为 PAID。
- 发布 OrderPaidEvent，可以先使用应用内事件或 Kafka adapter。

不做：
- 不实现退款。
- 不实现分账。
- 不实现支付超时关闭。

验收标准：
- 重复回调只处理一次。
- 签名错误返回 401。
- 订单不存在返回明确错误。
- OrderPaidEvent 只发布一次。

完成前：
- 运行 ./gradlew test。
- 运行 ./gradlew integrationTest。
- 运行 ./gradlew clean check。
- 更新 PROGRESS.md。
```

完成后检查并提交：

```bash
./gradlew clean check
git diff
git add .
git commit -m "feat: implement payment callback flow"
```

## 第 19 步：如果 Agent 失败了怎么办

不要只说“模型不行”。按下面方式处理。

创建失败日志：

```bash
touch docs/harness/failure-log.md
```

Windows PowerShell：

```powershell
New-Item -ItemType File -Path docs/harness/failure-log.md
```

记录格式：

```markdown
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
```

失败后要做的是修 Harness：

| 失败现象 | 下一步 |
|---|---|
| Agent 做超范围 | 强化 feature list 的 out_of_scope |
| Agent 违反分层 | 增加 ARCHITECTURE.md 和 ArchUnit |
| Agent 不写测试 | 强化 Definition of Done |
| Agent 跳过验证 | 强化 AGENTS.md 完成规则 |
| Agent 不知道下一步 | 更新 PROGRESS.md 和执行计划 |

## 第 20 步：每次会话结束前交接

要求 Agent 最后必须更新 `PROGRESS.md`，并在回复中包含：

```text
请在结束前完成交接：
1. 更新 PROGRESS.md。
2. 说明本次完成了什么。
3. 说明还有什么没完成。
4. 说明实际运行了哪些验证命令。
5. 说明下一步建议做什么。
```

`PROGRESS.md` 示例：

```markdown
## 已完成

- 完成 F001 创建待支付订单。
- 新增订单领域模型。
- 新增订单创建 API。
- 新增单元测试和集成测试。

## 待完成

- F002 支付成功回调。
- OrderPaidEvent 发布。

## 验证记录

- ./gradlew test 通过。
- ./gradlew integrationTest 通过。

## 下一步

执行 docs/exec-plans/active/order-payment-flow.md 的 Step 5。
```

## 第 21 步：把流程复制到其他项目

其他项目落地时，不要复制业务代码，只复制 Harness 结构：

```text
AGENTS.md
ARCHITECTURE.md
PROGRESS.md
docs/harness/definition-of-done.md
docs/harness/feature-list-xxx.json
docs/harness/failure-log.md
docs/product-specs/xxx.md
docs/exec-plans/active/xxx.md
Makefile
```

然后替换：

- 项目概览
- 技术栈
- 架构约束
- 业务需求
- 功能清单
- 验证命令

## 最小可执行路径

如果你想快速试一遍，最小路径是：

1. 创建空白目录。
2. 初始化 Git。
3. 创建 `AGENTS.md`。
4. 创建 `PROGRESS.md`。
5. 创建 `ARCHITECTURE.md`。
6. 创建 `docs/product-specs/order-payment-flow.md`。
7. 创建 `docs/harness/feature-list-order-payment.json`。
8. 创建 `docs/exec-plans/active/order-payment-flow.md`。
9. 让 Agent 初始化并总结下一步。
10. 让 Agent 只做 Step 0。
11. 验证通过后提交。
12. 再让 Agent 做 Step 1。

不要一开始就让 Agent “帮我实现完整订单支付系统”。这会让范围、验证、状态全部失控。

## 一句话记忆

**先建轨道，再跑火车；先写规则，再写代码；先做小切片，再扩完整链路。**
