# Harness 完整链路实战步骤：订单创建与支付确认场景

> 面向 Java 架构师、Tech Lead、平台工程团队。本文把 Harness 最佳实践拆成可执行步骤，后续项目可以直接复用为落地剧本。

## 0. 业务场景

本文选用一个真实常见的电商业务场景：

**用户创建订单，系统锁定库存，生成待支付订单；支付平台回调成功后，系统确认支付、扣减库存、发布订单已支付事件。**

这个场景覆盖了 Java 后端项目中最容易出问题的关键点：

- REST API 设计
- 领域建模
- 分布式事务取舍
- 库存一致性
- 支付回调幂等
- 数据库 migration
- 消息事件
- 异常与错误码
- 安全校验
- 可观测性
- 单元测试、集成测试、契约测试
- Agent 跨会话协作

目标不是一次性让 Agent “写完整个系统”，而是建立一条可复用的 Harness 工作链路，让 Agent 在明确规则、明确边界、可验证反馈中逐步完成真实工程任务。

## 1. 最终交付目标

本实战最终交付以下能力：

1. 用户提交订单创建请求。
2. 系统校验商品、价格、用户身份和库存。
3. 系统创建待支付订单。
4. 系统锁定库存。
5. 支付成功回调可以幂等处理。
6. 系统确认订单已支付。
7. 系统扣减库存并发布 `OrderPaidEvent`。
8. 所有核心路径具备测试、日志、指标和错误处理。

## 2. 推荐技术栈

可按项目实际替换，但必须写进仓库。

```text
JDK: 21
Framework: Spring Boot 3.x
Build: Gradle
Database: PostgreSQL
Cache: Redis
Message: Kafka
Migration: Flyway
Test: JUnit 5, AssertJ, Mockito, Testcontainers
Architecture Test: ArchUnit
Observability: Micrometer, OpenTelemetry
```

## 3. 总体执行链路

完整 Harness 链路分为 12 个阶段：

1. 冷启动审计
2. 建立最小 Harness
3. 固化架构规则
4. 拆分业务功能清单
5. 建立执行计划
6. 初始化 Agent 工作会话
7. 执行第一个垂直切片
8. 运行验证反馈闭环
9. 修补 Harness 缺口
10. 扩展到完整业务链路
11. 交接与状态持久化
12. 复盘和模板沉淀

每个阶段都要有：

- 输入
- 操作步骤
- 产物
- 验证命令
- 退出条件

## 4. 阶段一：冷启动审计

### 目标

确认当前仓库是否能让一个新 Agent 不依赖口头解释就开始工作。

### 输入

- 现有代码仓库
- 当前业务需求
- 当前构建和测试命令

### 操作步骤

1. 开启一个全新 Agent 会话。
2. 不提供口头背景，只让 Agent 阅读仓库。
3. 要求 Agent 回答以下问题：
   - 这是什么系统？
   - 技术栈是什么？
   - 模块如何划分？
   - 如何本地启动？
   - 如何运行测试？
   - 核心架构约束是什么？
   - 当前任务进展在哪里？
4. 记录 Agent 答不上来的问题。
5. 将缺口归类到五类 Harness 问题：
   - 指令缺失
   - 工具缺失
   - 环境缺失
   - 状态缺失
   - 反馈缺失

### 产物

```text
docs/harness/cold-start-audit.md
```

示例：

```markdown
# Cold Start Audit

## Agent 无法回答的问题
- 不知道订单服务的事务边界。
- 不知道支付回调是否要求幂等。
- 不知道完整验证命令。
- 不知道库存锁定是同步调用还是事件驱动。

## 缺口归因
- 指令缺失：缺少 ARCHITECTURE.md
- 反馈缺失：缺少 integrationTest 命令
- 状态缺失：缺少 PROGRESS.md
```

### 验证命令

无代码命令。验证方式是人工检查冷启动问题是否被记录。

### 退出条件

- 所有冷启动缺口都已记录。
- 每个缺口都归因到 Harness 五个子系统之一。

## 5. 阶段二：建立最小 Harness

### 目标

让 Agent 有一个稳定入口，知道项目规则、命令、边界和完成定义。

### 输入

- 冷启动审计结果
- 项目实际技术栈
- 团队现有工程规范

### 操作步骤

1. 创建 `AGENTS.md`。
2. 创建 `PROGRESS.md`。
3. 创建 `Makefile` 或标准脚本。
4. 创建 `docs/harness/definition-of-done.md`。
5. 将完整验证命令写入 `AGENTS.md`。

### 产物一：`AGENTS.md`

```markdown
# AGENTS.md

## 项目概览
- 本项目是电商订单域服务，负责订单创建、库存锁定、支付确认和订单事件发布。
- 技术栈：JDK 21, Spring Boot 3.x, Gradle, PostgreSQL, Redis, Kafka, Flyway。

## 必读文件
- 架构规则：ARCHITECTURE.md
- 当前状态：PROGRESS.md
- 完成定义：docs/harness/definition-of-done.md
- 安全规则：docs/SECURITY.md
- 可靠性规则：docs/RELIABILITY.md

## 硬约束
- Controller 不得直接访问 Repository。
- Domain 层不得依赖 Spring Web、JPA、MyBatis、Kafka。
- 支付回调必须幂等。
- 库存锁定和扣减必须有一致性设计说明。
- 所有数据库结构变更必须通过 Flyway。
- 不得为了通过测试而删除或降低业务校验。

## 标准命令
- 构建：./gradlew clean build
- 单元测试：./gradlew test
- 集成测试：./gradlew integrationTest
- 架构测试：./gradlew archTest
- 完整验证：./gradlew clean check

## 完成规则
- 未运行相关验证命令，不得声明完成。
- 验证失败时必须先定位原因，再修改代码。
- 会话结束前必须更新 PROGRESS.md。
```

### 产物二：`PROGRESS.md`

```markdown
# PROGRESS.md

## 当前目标
实现订单创建与支付确认链路。

## 已完成
- 尚无

## 进行中
- 建立 Harness 基础文件

## 待完成
- 订单创建 API
- 库存锁定
- 支付回调幂等处理
- 订单已支付事件
- 测试与验证

## 阻塞项
- 无

## 验证记录
- 尚未执行
```

### 产物三：`Makefile`

```makefile
.PHONY: init test integration arch check

init:
	./gradlew --version
	git status --short

test:
	./gradlew test

integration:
	./gradlew integrationTest

arch:
	./gradlew archTest

check:
	./gradlew clean check
```

### 验证命令

```bash
make init
```

### 退出条件

- Agent 可以通过 `AGENTS.md` 找到所有关键入口。
- 完整验证命令已经明确。
- 当前状态已经写入 `PROGRESS.md`。

## 6. 阶段三：固化架构规则

### 目标

把架构师脑中的规则写进仓库，并尽量变成可执行测试。

### 输入

- 当前系统分层设计
- 模块依赖关系
- 业务边界

### 操作步骤

1. 创建根目录 `ARCHITECTURE.md`。
2. 明确订单域模块边界。
3. 明确包结构。
4. 明确事务边界。
5. 明确事件发布规则。
6. 使用 ArchUnit 固化关键规则。

### 产物一：`ARCHITECTURE.md`

```markdown
# ARCHITECTURE.md

## 分层模型

```text
controller -> application -> domain -> infrastructure
```

## 各层职责

- controller：HTTP 入参校验、认证信息提取、响应转换。
- application：编排用例、事务边界、调用 domain 和 infrastructure port。
- domain：领域模型、领域规则、状态转换。
- infrastructure：数据库、Redis、Kafka、外部支付平台适配。

## 订单创建规则

- 订单创建必须先校验商品状态和价格快照。
- 库存锁定成功后才能创建待支付订单。
- 订单初始状态为 `PENDING_PAYMENT`。

## 支付确认规则

- 支付回调必须根据 payment transaction id 幂等。
- 重复回调不得重复扣减库存。
- 订单从 `PENDING_PAYMENT` 转为 `PAID` 后发布 `OrderPaidEvent`。
```
```

### 产物二：ArchUnit 测试

```java
class ArchitectureRulesTest {

    @Test
    void domain_should_not_depend_on_infrastructure() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..controller..")
            .check(importedClasses);
    }

    @Test
    void controller_should_not_depend_on_repository() {
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .check(importedClasses);
    }
}
```

### 验证命令

```bash
./gradlew archTest
```

### 退出条件

- 架构边界有文档。
- 至少 2 条关键架构规则有自动化测试。
- Agent 违反架构边界时会被测试拦截。

## 7. 阶段四：拆分业务功能清单

### 目标

防止 Agent 过度发挥或做不完整，把业务目标拆成明确功能单元。

### 输入

- 业务需求
- 架构约束
- 当前系统能力

### 操作步骤

1. 创建 `docs/product-specs/order-payment-flow.md`。
2. 创建 `docs/harness/feature-list-order-payment.json`。
3. 明确 scope 和 out_of_scope。
4. 为每个功能写验收标准。
5. 给每个功能标注验证命令。

### 产物：`feature-list-order-payment.json`

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
        "生成价格快照",
        "创建 PENDING_PAYMENT 订单"
      ],
      "out_of_scope": [
        "不接入真实支付平台",
        "不实现优惠券",
        "不实现跨境税费"
      ],
      "acceptance_criteria": [
        "库存不足返回明确错误码",
        "非法商品返回 400",
        "创建成功返回 orderId 和 paymentToken",
        "包含单元测试和集成测试"
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
        "幂等处理 transactionId",
        "订单状态变更为 PAID",
        "发布 OrderPaidEvent"
      ],
      "out_of_scope": [
        "不处理退款",
        "不处理分账",
        "不处理支付超时关闭"
      ],
      "acceptance_criteria": [
        "重复回调只处理一次",
        "签名错误返回 401",
        "订单不存在返回明确错误码",
        "事件只发布一次"
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

### 验证命令

无代码命令。由架构师或 Tech Lead 审查功能清单。

### 退出条件

- 每个功能有明确 scope。
- 每个功能有明确 out_of_scope。
- 每个功能有可验证验收标准。
- 每个功能能独立交付。

## 8. 阶段五：建立执行计划

### 目标

把功能清单转换成 Agent 可执行的任务计划。

### 输入

- `feature-list-order-payment.json`
- `ARCHITECTURE.md`
- 当前代码结构

### 操作步骤

1. 创建 `docs/exec-plans/active/order-payment-flow.md`。
2. 按垂直切片拆任务。
3. 每个任务包含修改文件、验证命令和回滚策略。
4. 明确每一步完成后要更新 `PROGRESS.md`。

### 产物：执行计划

```markdown
# Exec Plan: Order Payment Flow

## Step 1: 领域模型
- 新增 Order、OrderItem、OrderStatus
- 新增 PaymentTransaction
- 新增领域方法 markPaid()
- 验证：./gradlew test

## Step 2: 数据库迁移
- 新增 orders 表
- 新增 order_items 表
- 新增 payment_transactions 表
- 验证：./gradlew integrationTest

## Step 3: 创建订单用例
- 新增 CreateOrderCommand
- 新增 CreateOrderService
- 新增 OrderRepository port
- 新增库存锁定 port
- 验证：./gradlew test ./gradlew integrationTest

## Step 4: HTTP API
- 新增 OrderController
- 新增请求响应 DTO
- 新增错误码映射
- 验证：./gradlew integrationTest

## Step 5: 支付回调
- 新增 PaymentCallbackController
- 新增 PaymentCallbackService
- 实现 transactionId 幂等
- 发布 OrderPaidEvent
- 验证：./gradlew clean check
```

### 验证命令

无代码命令。由人工审查计划是否满足“小步、可验证、可回滚”。

### 退出条件

- 每一步都能单独执行。
- 每一步都有验证命令。
- 每一步失败后都能定位。

## 9. 阶段六：初始化 Agent 工作会话

### 目标

让 Agent 每次开始工作前进入一致状态。

### 输入

- `AGENTS.md`
- `PROGRESS.md`
- 执行计划
- Git 工作区

### 操作步骤

每次会话开始时，让 Agent 执行：

```text
请先初始化：
1. 阅读 AGENTS.md。
2. 阅读 ARCHITECTURE.md。
3. 阅读 PROGRESS.md。
4. 阅读 docs/exec-plans/active/order-payment-flow.md。
5. 查看 git status。
6. 运行 make init。
7. 总结当前应执行的下一步，不要直接改代码。
```

### 验证命令

```bash
make init
git status --short
```

### 退出条件

- Agent 能说清当前任务。
- Agent 能说清下一步。
- Agent 没有忽略用户已有改动。
- Agent 没有越过计划直接大改。

## 10. 阶段七：执行第一个垂直切片

### 目标

先做最小可验证路径，不一次性铺开全部代码。

建议第一个切片：

**创建待支付订单：domain + migration + repository + service + controller + integration test。**

### 输入

- 执行计划 Step 1 到 Step 4
- 功能 F001

### 操作步骤

1. 新增领域模型。
2. 新增数据库 migration。
3. 新增 repository port 和实现。
4. 新增 application service。
5. 新增 controller。
6. 新增单元测试。
7. 新增集成测试。
8. 运行验证。
9. 更新 `PROGRESS.md`。

### Agent 任务提示模板

```text
请执行 F001：创建待支付订单。

范围：
- 实现 POST /api/v1/orders。
- 创建 PENDING_PAYMENT 订单。
- 库存不足时返回明确错误码。
- 生成价格快照。

不做：
- 不实现支付回调。
- 不实现优惠券。
- 不接入真实支付平台。

必须遵守：
- 阅读 AGENTS.md 和 ARCHITECTURE.md。
- Controller 不得直接访问 Repository。
- Domain 层不得依赖 Spring。
- 数据库变更必须使用 Flyway。

完成定义：
- 单元测试覆盖领域状态转换。
- 集成测试覆盖 API 成功和库存不足。
- 运行 ./gradlew test 和 ./gradlew integrationTest。
- 更新 PROGRESS.md。
```

### 验证命令

```bash
./gradlew test
./gradlew integrationTest
```

### 退出条件

- F001 所有验收标准通过。
- 测试真实运行且通过。
- `PROGRESS.md` 已更新。
- 未做 out_of_scope 内容。

## 11. 阶段八：运行验证反馈闭环

### 目标

将失败视为 Harness 缺口，而不是简单归因于模型。

### 输入

- 验证命令输出
- 测试失败日志
- Agent 修改记录

### 操作步骤

1. 运行完整或局部验证命令。
2. 若失败，记录失败类型。
3. 判断失败属于哪一类：
   - 需求没写清
   - 架构约束缺失
   - 环境不可复现
   - 测试反馈不足
   - 状态文件过期
4. 修补对应 Harness 文件。
5. 重新运行验证。

### 产物：`docs/harness/failure-log.md`

```markdown
# Harness Failure Log

## 2026-06-29 F001

### 现象
集成测试失败，库存不足时返回 500。

### 原因
错误码规范没有写入仓库，Agent 自行使用 RuntimeException。

### 归因
指令缺失。

### 修复
- 新增 docs/ERROR_CODES.md。
- 在 AGENTS.md 中加入“业务异常必须映射统一错误码”。
- 增加库存不足集成测试。

### 防复发验证
./gradlew integrationTest 通过。
```

### 验证命令

```bash
./gradlew test
./gradlew integrationTest
./gradlew clean check
```

### 退出条件

- 失败原因被归因到 Harness 子系统。
- 对应 Harness 文件已修补。
- 验证重新通过。

## 12. 阶段九：修补 Harness 缺口

### 目标

每次失败后都让项目 Harness 变强。

### 常见修补方式

| 失败现象 | Harness 修补 |
|---|---|
| Agent 写错分层 | 增加 ARCHITECTURE.md 和 ArchUnit |
| Agent 忘记幂等 | 在 RELIABILITY.md 增加幂等规则 |
| Agent 不写测试 | 在 Definition of Done 增加测试要求 |
| Agent 跑错命令 | 在 AGENTS.md 统一验证命令 |
| Agent 重复探索项目 | 补充模块级 ARCHITECTURE.md |
| Agent 提前完成 | 要求最终回复列出验证命令和结果 |
| Agent 修改过大 | 使用 feature list 限制 scope |

### 退出条件

- 同类失败再次出现的概率下降。
- 规则不是只写在聊天里，而是进入仓库。

## 13. 阶段十：扩展到完整业务链路

### 目标

在第一个切片稳定后，继续实现支付确认。

### Agent 任务提示模板

```text
请执行 F002：支付成功回调。

范围：
- 实现 POST /api/v1/payments/callback。
- 校验支付签名。
- 使用 transactionId 做幂等。
- 将订单从 PENDING_PAYMENT 变更为 PAID。
- 发布 OrderPaidEvent。

不做：
- 不处理退款。
- 不实现支付超时关闭。
- 不实现分账。

必须验证：
- 重复回调只处理一次。
- 签名错误返回 401。
- 订单不存在返回明确错误码。
- OrderPaidEvent 只发布一次。
- ./gradlew clean check 通过。
```

### 推荐测试用例

| 用例 | 类型 |
|---|---|
| 支付成功后订单变为 PAID | 集成测试 |
| 重复回调不重复发布事件 | 集成测试 |
| 签名错误返回 401 | API 测试 |
| 非待支付订单不允许再次支付 | 单元测试 |
| transactionId 唯一约束生效 | 集成测试 |

### 验证命令

```bash
./gradlew test
./gradlew integrationTest
./gradlew clean check
```

### 退出条件

- F002 所有验收标准通过。
- 幂等行为有测试证明。
- 事件发布行为有测试证明。
- 完整验证通过。

## 14. 阶段十一：交接与状态持久化

### 目标

确保会话关闭后，下一次 Agent 可以无缝继续。

### 操作步骤

每次会话结束前，Agent 必须更新：

1. `PROGRESS.md`
2. 当前执行计划
3. 失败日志
4. 验证记录
5. 未完成事项

### 交接模板

```markdown
## Session Handoff

### 本次完成
- 完成 F001 创建待支付订单。
- 新增订单领域模型、migration、controller 和集成测试。

### 本次验证
- ./gradlew test 通过。
- ./gradlew integrationTest 通过。

### 未完成
- F002 支付成功回调。
- OrderPaidEvent 消费方尚未实现。

### 风险
- 库存锁定目前是同步调用，后续高并发下需要压测。

### 下一步建议
- 执行 docs/exec-plans/active/order-payment-flow.md 的 Step 5。
```

### 退出条件

- 下一会话只读仓库即可恢复上下文。
- 没有只存在于聊天记录里的关键信息。

## 15. 阶段十二：复盘和模板沉淀

### 目标

把本次业务场景沉淀为团队通用 Harness 模板。

### 操作步骤

1. 统计 Agent 执行成功率。
2. 统计验证失败类型。
3. 统计人工介入次数。
4. 更新团队模板。
5. 将可复用规则推广到其他项目。

### 复盘指标

| 指标 | 说明 |
|---|---|
| 一次通过率 | Agent 首次提交后验证通过比例 |
| 返工次数 | 每个功能平均修复轮数 |
| 提前完成率 | Agent 声称完成但验证失败比例 |
| 冷启动耗时 | 新会话理解项目所需时间 |
| Harness 缺口数 | 本次发现并修补的缺口数量 |
| 自动化验证覆盖 | 有命令可验证的验收标准比例 |

### 退出条件

- 模板已更新。
- 失败经验已进入仓库。
- 其他项目可以复制该流程。

## 16. 可复制目录模板

```text
project/
├── AGENTS.md
├── ARCHITECTURE.md
├── PROGRESS.md
├── Makefile
├── docs/
│   ├── SECURITY.md
│   ├── RELIABILITY.md
│   ├── ERROR_CODES.md
│   ├── harness/
│   │   ├── cold-start-audit.md
│   │   ├── definition-of-done.md
│   │   ├── failure-log.md
│   │   └── feature-list-order-payment.json
│   ├── product-specs/
│   │   └── order-payment-flow.md
│   └── exec-plans/
│       ├── active/
│       │   └── order-payment-flow.md
│       └── completed/
├── src/
│   ├── main/java/...
│   └── test/java/...
└── build.gradle
```

## 17. 项目启动检查清单

在任何 Java 项目引入 Harness 前，先完成以下检查：

```markdown
## Harness Readiness Checklist

- [ ] 仓库根目录有 AGENTS.md。
- [ ] AGENTS.md 写明技术栈、硬约束、验证命令。
- [ ] 有 ARCHITECTURE.md 描述分层和模块边界。
- [ ] 有 PROGRESS.md 记录当前状态。
- [ ] 有明确 Definition of Done。
- [ ] 有标准构建命令。
- [ ] 有标准测试命令。
- [ ] 有完整验证命令。
- [ ] 有功能清单模板。
- [ ] 有执行计划模板。
- [ ] 有失败归因日志。
- [ ] 有会话交接模板。
- [ ] 至少一条架构规则被自动化测试覆盖。
- [ ] CI 会执行完整验证。
```

## 18. 推荐的 Agent 工作协议

后续所有项目可以直接复制以下协议到 `AGENTS.md`：

```markdown
## Agent 工作协议

### 开始前
- 必须阅读 AGENTS.md。
- 必须阅读与任务相关的 ARCHITECTURE.md。
- 必须阅读 PROGRESS.md。
- 必须查看 git status。
- 必须确认当前任务 scope 和 out_of_scope。

### 执行中
- 每次只完成一个可验证功能切片。
- 遇到失败先记录现象，再定位原因。
- 不得绕过测试或删除测试来让构建通过。
- 不得扩大任务范围。
- 不得覆盖用户已有改动。

### 完成前
- 必须运行任务要求的验证命令。
- 必须更新 PROGRESS.md。
- 必须说明完成了什么、没有完成什么。
- 必须列出实际运行的验证命令和结果。

### 禁止
- 禁止未验证就宣称完成。
- 禁止把临时聊天信息当作长期知识。
- 禁止把安全规则降级为建议。
- 禁止在没有执行计划时大规模重构。
```

## 19. 一句话落地原则

**业务需求进功能清单，架构规则进仓库文档，质量要求进验证命令，执行过程进状态文件，失败经验进 Harness 模板。**

做到这五点，Agent 才能在真实 Java 项目里稳定参与工程交付。
