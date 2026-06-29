# Harness 最佳工程实践（Java 架构师版）

> 基于 `learn-harness-engineering-zh.pdf` 整理，面向 Java 架构师、技术负责人和平台工程团队。

## 1. 核心认知

Harness Engineering 的目标不是“让模型更聪明”，而是为 AI Coding Agent 建立一套可执行、可验证、可恢复、可观测的工程工作系统。

在真实 Java 项目中，Agent 失败通常不是因为模型能力不足，而是因为缺少以下工程支撑：

- 任务边界不清晰
- 架构约束没有写进仓库
- 构建、测试、启动命令不统一
- 没有明确的完成定义
- 长任务跨会话时上下文丢失
- Agent 自称完成，但没有经过真实验证

因此，最佳实践的核心原则是：

**先修 Harness，再换模型。**

## 2. Harness 五个子系统

一个可靠的 Harness 至少包含五个子系统：

| 子系统 | 作用 | Java 项目实践 |
|---|---|---|
| 指令 | 告诉 Agent 项目规则、边界和约束 | `AGENTS.md`、`ARCHITECTURE.md`、模块说明 |
| 工具 | 给 Agent 可调用的标准操作 | Maven/Gradle、Makefile、Docker、脚本 |
| 环境 | 保证运行环境可复现 | JDK 版本、Spring Boot 版本、数据库、Redis、Testcontainers |
| 状态 | 保持跨会话连续性 | `PROGRESS.md`、执行计划、功能清单 |
| 反馈 | 用真实结果纠偏 | 单测、集成测试、契约测试、lint、构建、E2E |

推荐优先建设顺序：

1. 反馈子系统：先让 Agent 知道怎么验证。
2. 指令子系统：再让 Agent 知道怎么做。
3. 环境子系统：保证每次执行一致。
4. 状态子系统：支持长任务和多人协作。
5. 工具子系统：沉淀标准化操作入口。

## 3. 仓库即规范

对 Agent 来说，不在仓库里的知识等于不存在。

Java 架构师需要把以下隐性知识写入仓库：

- 技术栈版本：JDK、Spring Boot、Spring Cloud、MyBatis/JPA、消息队列、数据库
- 分层架构：controller、application、domain、infrastructure 的职责边界
- 模块依赖规则：哪些模块可以依赖，哪些禁止反向依赖
- 事务边界：事务放在 application service 还是 domain service
- 异常规范：业务异常、系统异常、错误码、HTTP 状态码映射
- API 规范：REST 风格、分页格式、幂等设计、鉴权方式
- 数据库规范：迁移工具、索引原则、软删除、审计字段
- 安全约束：敏感信息、SQL 注入、越权、日志脱敏
- 验证命令：测试、构建、静态扫描、集成测试

建议仓库结构：

```text
project/
├── AGENTS.md
├── ARCHITECTURE.md
├── Makefile
├── PROGRESS.md
├── docs/
│   ├── DESIGN.md
│   ├── SECURITY.md
│   ├── RELIABILITY.md
│   ├── QUALITY_SCORE.md
│   ├── product-specs/
│   └── exec-plans/
├── service-user/
│   ├── ARCHITECTURE.md
│   └── src/
├── service-order/
│   ├── ARCHITECTURE.md
│   └── src/
└── build.gradle / pom.xml
```

## 4. `AGENTS.md` 最佳实践

`AGENTS.md` 应该是 Agent 的入口文件，不是百科全书。

建议控制在 50 到 200 行，主要承担“路由器”职责：

```markdown
# AGENTS.md

## 项目概览
- 这是一个基于 Spring Boot 3.x 的微服务系统。
- 使用 JDK 21、Gradle、PostgreSQL、Redis、Kafka。
- 领域边界包括 user、order、payment、inventory。

## 必读文件
- 架构总览：ARCHITECTURE.md
- 安全规则：docs/SECURITY.md
- 可靠性规则：docs/RELIABILITY.md
- 当前进度：PROGRESS.md

## 硬约束
- 不得绕过 application service 直接在 controller 中访问 repository。
- 不得在 domain 层依赖 Spring Web、MyBatis、JPA 注解。
- 所有外部接口必须有超时、重试或降级策略。
- 所有数据库变更必须通过 Flyway/Liquibase。
- 不得提交明文密钥、token、密码。

## 常用命令
- 构建：./gradlew clean build
- 单元测试：./gradlew test
- 集成测试：./gradlew integrationTest
- 静态检查：./gradlew check
- 完整验证：./gradlew clean check

## 完成定义
- 代码实现符合当前任务范围。
- 新增或修改的逻辑有测试覆盖。
- `./gradlew clean check` 通过。
- 相关文档、进度文件已更新。
```

关键原则：

- 硬约束放前面。
- 只写稳定规则，不写临时需求。
- 详细内容拆到专门文件。
- 避免一个巨型指令文件持续膨胀。

## 5. 架构文档分层

推荐将文档分为三层。

### 5.1 全局架构文档

`ARCHITECTURE.md` 描述系统总体结构：

- 系统边界
- 服务拆分
- 领域模型
- 调用链路
- 数据流
- 模块依赖规则
- 关键架构决策

### 5.2 模块级架构文档

每个服务或模块下放一个局部 `ARCHITECTURE.md`：

```text
service-order/
├── ARCHITECTURE.md
└── src/main/java/...
```

内容包括：

- 模块职责
- 对外接口
- 内部包结构
- 依赖的其他模块或服务
- 事务边界
- 领域不变量
- 常见修改入口

### 5.3 专项约束文档

建议至少维护：

- `docs/SECURITY.md`：认证、授权、脱敏、密钥、输入校验
- `docs/RELIABILITY.md`：超时、重试、熔断、幂等、补偿
- `docs/DESIGN.md`：设计原则、交互规范、接口规范
- `docs/QUALITY_SCORE.md`：质量评分标准

## 6. 功能清单驱动开发

PDF 中强调用功能清单约束 Agent 该做什么。Java 项目中可以使用 `feature_list.json` 或 Markdown 表格。

示例：

```json
{
  "feature": "用户偏好设置接口",
  "scope": [
    "新增 GET /api/v1/users/{id}/preferences",
    "新增 PUT /api/v1/users/{id}/preferences",
    "支持主题、语言、通知开关"
  ],
  "out_of_scope": [
    "不修改用户注册流程",
    "不新增前端页面",
    "不调整认证机制"
  ],
  "acceptance_criteria": [
    "未登录访问返回 401",
    "访问他人偏好返回 403",
    "参数非法返回统一错误码",
    "新增单元测试和集成测试",
    "./gradlew clean check 通过"
  ]
}
```

最佳实践：

- 每个任务必须有明确范围。
- 明确“不做什么”。
- 验收标准必须可验证。
- 大任务拆成多个小功能。

## 7. 完成定义

不要让 Agent 自己判断“完成”。

每个任务都应写明 Definition of Done：

```markdown
## Definition of Done

- API 行为符合接口契约。
- 单元测试覆盖正常路径、异常路径、边界条件。
- 涉及数据库变更时包含 migration。
- 涉及外部调用时包含超时、失败处理和日志。
- 所有新增公共方法有清晰命名和必要注释。
- `./gradlew clean check` 通过。
- `PROGRESS.md` 已更新。
```

对于 Java 后端，建议默认验证链路：

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew check
./gradlew bootJar
```

如果是 Maven：

```bash
./mvnw clean test
./mvnw verify
```

## 8. 端到端验证闭环

PDF 明确指出：跑通完整流程才算真正验证。

Java 项目不要只依赖“代码看起来对”。建议分层验证：

| 层级 | 工具 | 目的 |
|---|---|---|
| 单元测试 | JUnit 5、Mockito、AssertJ | 验证局部逻辑 |
| 集成测试 | SpringBootTest、Testcontainers | 验证数据库、Redis、MQ 等真实依赖 |
| 契约测试 | Spring Cloud Contract、Pact | 验证服务间契约 |
| API 测试 | REST Assured、Postman/Newman | 验证 HTTP 行为 |
| 静态检查 | Checkstyle、SpotBugs、PMD、ArchUnit | 验证代码质量和架构规则 |
| 构建验证 | Maven/Gradle、Docker build | 验证可交付 |

特别推荐使用 ArchUnit 固化架构边界：

```java
@Test
void domain_should_not_depend_on_infrastructure() {
    noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
        .check(importedClasses);
}
```

这类测试非常适合 Harness，因为 Agent 违反架构规则时能被自动拦住。

## 9. 初始化流程

每次 Agent 开始工作前，都应该执行初始化。

推荐流程：

1. 阅读 `AGENTS.md`
2. 阅读当前任务相关的 `ARCHITECTURE.md`
3. 阅读 `PROGRESS.md`
4. 查看 Git 状态
5. 运行最小健康检查
6. 确认任务范围和完成定义

可以提供 `init.sh` 或 `make init`：

```bash
./gradlew --version
./gradlew test --dry-run
docker compose ps
git status --short
```

Java 项目建议初始化时确认：

- JDK 版本是否匹配
- Gradle/Maven wrapper 是否可用
- 本地数据库、Redis、MQ 是否可用
- 测试容器是否可启动
- 当前分支是否干净或存在用户改动

## 10. 跨会话状态管理

长任务必须有状态文件。建议使用 `PROGRESS.md`：

```markdown
# PROGRESS.md

## 当前目标
实现用户偏好设置接口。

## 已完成
- 新增 UserPreference 实体
- 新增数据库 migration
- 新增 repository

## 进行中
- 实现 application service

## 待完成
- controller
- 单元测试
- 集成测试
- API 文档

## 阻塞项
- 需要确认偏好字段是否支持多语言 fallback

## 验证记录
- 2026-06-29: ./gradlew test 通过
- 2026-06-29: integrationTest 尚未执行
```

要求：

- 每次会话开始先读。
- 每次会话结束前更新。
- 阻塞项要明确。
- 验证结果要写真实命令和结果。

## 11. 防止 Agent 提前宣告完成

常见风险：

- 只实现代码，不写测试
- 测试没有运行
- 只跑局部测试，没有跑完整验证
- 忽略集成依赖
- 文档和进度没有更新

治理方式：

- 在 `AGENTS.md` 写清“未运行验证不得声明完成”。
- 让 Agent 在最终回复中列出已运行命令。
- CI 中强制执行完整验证。
- 对关键模块设置质量门禁。
- 使用 PR 模板检查完成定义。

PR 模板示例：

```markdown
## 变更范围

## 验证命令
- [ ] ./gradlew clean test
- [ ] ./gradlew integrationTest
- [ ] ./gradlew check

## 风险

## 文档更新
- [ ] AGENTS.md / ARCHITECTURE.md / PROGRESS.md 已按需更新
```

## 12. 可观测性与反馈闭环

Harness 不只服务编码，也服务运行反馈。

Java 后端建议将以下规则写入 `RELIABILITY.md`：

- 所有外部调用必须有 timeout。
- 所有重试必须有最大次数和退避策略。
- 所有异步消费必须考虑幂等。
- 所有关键业务链路必须有结构化日志。
- 所有跨服务请求必须传递 trace id。
- 所有异常必须映射为统一错误响应。

推荐技术栈：

- 日志：Logback + JSON encoder
- 指标：Micrometer + Prometheus
- 链路追踪：OpenTelemetry
- 告警：Grafana / Alertmanager
- 审计：业务审计表或审计事件

Agent 修改可靠性相关代码时，必须验证：

- 日志是否包含关键字段
- 指标是否有合理命名
- 异常是否被正确处理
- 超时、重试、熔断是否可配置

## 13. 安全 Harness

安全规则必须是硬约束，而不是建议。

`docs/SECURITY.md` 建议包含：

- 认证与授权模型
- RBAC/ABAC 规则
- 敏感字段定义
- 日志脱敏规则
- 密钥管理方式
- SQL 注入防护
- SSRF 防护
- 文件上传限制
- 越权测试要求

建议给 Agent 明确禁止项：

```markdown
## 禁止项
- 禁止在代码、测试、文档中写入真实密钥。
- 禁止拼接 SQL。
- 禁止在日志中打印 password、token、idCard、phone。
- 禁止绕过权限校验直接查询用户数据。
- 禁止为了让测试通过而降低安全检查。
```

## 14. 团队协作模式

Java 架构师可以把 Harness 作为团队工程规范的一部分。

建议角色分工：

| 角色 | 责任 |
|---|---|
| 架构师 | 定义架构边界、硬约束、质量门禁 |
| Tech Lead | 维护 `AGENTS.md`、模块文档、任务拆分 |
| 开发人员 | 按功能清单驱动 Agent 工作 |
| QA | 补充验收标准、E2E、回归用例 |
| DevOps | 维护环境、CI、镜像、部署验证 |

建议每个迭代做一次 Harness 复盘：

- 哪些 Agent 失败是任务不清？
- 哪些失败是文档缺失？
- 哪些失败是验证不足？
- 哪些失败是环境不可复现？
- 哪些规则应该沉淀到仓库？

## 15. Java 项目落地路线图

### 第 1 周：最小 Harness

- 创建 `AGENTS.md`
- 写清技术栈、运行命令、验证命令
- 建立 `PROGRESS.md`
- 明确 Definition of Done

### 第 2 周：架构知识入库

- 创建根 `ARCHITECTURE.md`
- 为核心服务创建模块级 `ARCHITECTURE.md`
- 整理安全、可靠性、数据库约束
- 把隐性规则从会议、聊天、个人经验迁入仓库

### 第 3 周：验证体系增强

- 统一 `./gradlew clean check` 或 `./mvnw verify`
- 补齐单元测试和集成测试入口
- 引入 Testcontainers
- 引入 ArchUnit 固化架构规则
- CI 强制质量门禁

### 第 4 周：长任务与治理

- 建立功能清单模板
- 建立执行计划模板
- 建立 PR 模板
- 建立 Harness 失败归因日志
- 每周复盘并修补 Harness

## 16. 衡量指标

建议跟踪以下指标：

- Agent 任务一次通过率
- Agent 提前宣告完成比例
- 任务平均返工次数
- 验证命令执行率
- CI 失败中 Harness 问题占比
- 新会话冷启动耗时
- 仓库外知识比例
- 架构规则自动化覆盖率

失败归因建议分为五类：

1. 任务规范问题
2. 上下文供给问题
3. 执行环境问题
4. 验证反馈问题
5. 状态管理问题

## 17. 总结

对 Java 架构师来说，Harness Engineering 本质上是把“架构治理、工程规范、质量门禁、知识管理、自动化验证”组合成 Agent 可执行的工作系统。

最佳实践可以压缩成一句话：

**把规则写进仓库，把验证做成命令，把状态持久化，把完成定义变成门禁。**

当这些基础设施稳定之后，AI Agent 才能从“会写代码的助手”变成“可被工程体系约束的开发参与者”。
