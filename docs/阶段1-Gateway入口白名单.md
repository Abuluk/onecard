# 阶段 1 Gateway 入口白名单

**状态**：开发骨架已实施并通过本地集成测试；生产入口仍受阶段门禁限制。

## 身份验证

- Gateway 只接受带签名的 OAuth 2.0 Access Token；验证 JWK 签名、issuer、audience 和有效期。
- 角色读取 Keycloak `realm_access.roles` 字符串数组。开发默认 issuer 为 `http://keycloak:8080/realms/onecard-dev`，audience 为 `onecard-api`，角色为 `onecard-user`、`onecard-admin`、`onecard-auditor`。
- `dev` profile 可用 `OIDC_ISSUER_URI`、`OIDC_JWK_SET_URI`、`OIDC_AUDIENCE` 和 `OIDC_ROLE_USER`、`OIDC_ROLE_ADMIN`、`OIDC_ROLE_AUDITOR` 覆盖示例值。`prod` profile 要求这些变量显式注入，不含默认 issuer、audience 或角色。
- Gateway 转发前删除 `X-User-Id`、`X-User-Roles`、`X-Authenticated-User`、`X-Authenticated-Roles`、`X-Auth-Request-User`、`X-Auth-Request-Roles`、`X-Forwarded-User`、`X-Principal` 和 `X-Roles`，避免下游把客户端输入当作已验证身份。

## 开发授权矩阵

| 路径 | 方法 | 角色 | 上游 | 边界 |
|---|---|---|---|---|
| `/api/account/**` | `GET` | user、admin、auditor | account-service | 账户查询骨架 |
| `/api/account/**` | `POST` | user、admin | account-service | 业务写接口尚未实现 |
| `/api/transactions/**` | `GET` | user、admin、auditor | transaction-service | 交易查询骨架 |
| `/api/transactions/**` | `POST` | user、admin | transaction-service | 业务写接口尚未实现 |
| `/api/payments/**` | `GET` | user、admin、auditor | payment-adapter-service | 支付查询骨架 |
| `/api/payments/**` | `POST` | user、admin | payment-adapter-service | `callback/**` 和 `callbacks/**` 对所有方法拒绝，未验签前不得接收渠道回调 |
| `/api/clearing/**` | `GET` | admin、auditor | clearing-service | 对账查询骨架 |
| `/api/clearing/**` | `POST` | admin | clearing-service | 清结算写接口尚未实现 |
| `/api/notifications/**` | `GET` | user、admin、auditor | notification-service | 通知查询骨架 |
| `/api/notifications/**` | `POST` | admin | notification-service | 管理操作骨架 |

所有未列出的方法和路径默认拒绝。`/api/risk/**`、`/api/access/**`、`/api/devices/**` 没有 Gateway 路由；内部服务管理端点也不做代理。公网监听为应用端口 8081。Actuator 仅暴露 health 和 Prometheus，并监听独立管理端口 9081；Compose 不将 9081 发布到宿主机，Categraf 仅在容器网络内访问它。

## 尚未解除的门槛

- 当前服务尚无业务 controller；本矩阵只约束开发骨架，不能作为资金 API 的生产授权矩阵。
- 正式上线前需把域级路径收窄为已评审的具体 API 路径和方法，并取得财务/风险对资金写操作的批准。
- 尚未配置共享存储的跨实例限流；支付、充值、退款等高风险操作在共享限流策略验证前不得对外开放。当前不启用依赖单实例内存的限流。
- 支付渠道回调在渠道验签与原始报文测试完成前保持拒绝；设备协议入口继续独立于用户 API。
- Keycloak 开发 realm/客户端导出和 Compose 登录联测尚待本地平台任务完成；本地自动化测试使用进程内临时 RSA/JWKS stub 验证资源服务器，不代表真实 IdP 联测或生产 OIDC 验收。
- 浏览器来源需求未确认，因此不启用 CORS。
