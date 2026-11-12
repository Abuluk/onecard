# ADR-0010：API 错误、W3C Trace Context 与运行指标

**状态**：阶段 1 开发基线部分实施；业务审计和业务指标仍待完成
**日期**：2026-11-10

## 背景

`common` 已定义 `Result<T>`，但认证、校验、下游故障和未预期错误曾由不同框架默认处理。阶段 1 还需要在 Servlet 服务与响应式 Gateway 间统一请求关联方式，并避免 Actuator/Prometheus 把高基数或主机细节作为指标暴露。当前服务仍是骨架，没有账户、交易或管理写操作。

## 决策

1. API 错误体沿用 `Result<T>`：`code`、`message`、`data`。错误消息仅给调用方安全摘要；拒绝值、异常消息、SQL、内部地址和凭据不进入响应。
2. 已实现的 HTTP 映射如下：

   | 情况 | HTTP | 错误码 |
   |---|---:|---|
   | 业务异常及参数/格式错误 | 400 | 业务码 / `VALIDATION_ERROR` |
   | 未认证 | 401 | `AUTHENTICATION_REQUIRED` |
   | 无权访问 | 403 | `ACCESS_DENIED` |
   | 资源不存在 / 方法不允许 / 冲突 | 404 / 405 / 409 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `CONFLICT` |
   | 限流 | 429 | `RATE_LIMITED` |
   | 下游连接失败 / 超时 | 503 / 504 | `SERVICE_UNAVAILABLE` / `DOWNSTREAM_TIMEOUT` |
   | 未预期异常 | 500 | `INTERNAL_ERROR` |

   Gateway 保留上游 5xx HTTP 状态码并重写为安全的 `Result` 错误体：上游 503/504 分别使用 `SERVICE_UNAVAILABLE` / `DOWNSTREAM_TIMEOUT`，其他上游 5xx 使用 `INTERNAL_ERROR`。上游错误体、编码和实体校验头不透传。

3. 所有 Gateway 与服务使用 W3C Trace Context `traceparent` 传播。有效入站上下文沿用 trace ID 并创建本服务 span；格式无效或被追踪器拒绝的头由追踪器生成新上下文，不回显不可信值。有效参与链路的请求在响应中返回当前服务 span 的 `traceparent`。日志使用 Micrometer trace/span 关联；响应式 Gateway 安全拒绝日志从当前请求 Observation 读取 trace ID。
4. 安全拒绝事件只记录固定事件名、服务名、HTTP 方法和 trace 关联；不记录 URI、查询参数、请求体、身份令牌、主体或原始异常消息。当前事件写应用日志，不构成不可篡改审计账本，也没有单独的保留/归档机制。
5. Prometheus 使用 MeterFilter 允许 HTTP server/client、JVM、进程、CPU 和 executor 平台指标。磁盘路径、Spring Security filter-chain 细节、trace ID 和凭据不作为指标标签暴露。指标端点仅通过私有管理端口供采集器访问。
6. 当前没有已批准的业务指标定义；待账户/交易业务切片给出稳定、低基数的业务状态后再添加业务计数与延迟指标，不以用户、卡号、交易号或 trace ID 作为 label。

7. Gateway 在用户 JWT 验证后，以 `sub` 作为主体维度在 Valkey 执行原子滚动窗口限流。阶段 1 暂定开发阈值为 GET 60 次/60 秒、POST 10 次/60 秒；Valkey 不可用时 fail-closed 返回 503。当前 Compose Valkey 是无持久化的单实例开发依赖，不能作为生产 HA 或高风险资金接口限额的验收结论。

## 后果与待办

- `common` 的 Servlet 异常处理、安全响应和指标过滤器适用于引入该模块的 Servlet 服务；Gateway 独立配置响应式安全错误和相同指标名称白名单，避免为 WebFlux 引入 MVC 运行依赖。
- 自动化测试覆盖 account/risk/Gateway 的认证授权失败、账户业务/校验/未知错误、W3C trace 请求/响应/下游传播、Gateway 下游异常分类及 500/503/504 上游错误体重写、审计日志 trace 关联及 Prometheus 指标白名单。
- Gateway 共享限流、真实 Valkey 429/主体隔离测试及 PR CI 门禁已实现；生产限额矩阵和高可用 Valkey 仍待评审验证。
- 资金变更和管理写操作出现前，必须定义独立业务审计记录、完整性保护、访问授权、保留和检索策略；应用日志不能替代该记录。
- 具体业务指标、生产日志保留期、采样率和外部可见 API 错误文案需在对应业务/运行环境评审中确认。

## 来源

- [W3C Trace Context](https://www.w3.org/TR/trace-context/)：`traceparent` 上下文格式与跨系统传播约定。
- [Spring Boot tracing reference](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html)：Micrometer Tracing、日志 trace/span 关联及采样配置。
- [Micrometer meter filters](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html)：通过 `MeterFilter` 限制注册指标。
