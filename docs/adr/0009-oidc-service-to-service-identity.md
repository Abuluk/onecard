# ADR-0009：服务间调用采用 OIDC Client Credentials

**状态**：开发基线已选；transaction Feign 调用端和 account/risk 接收端 JWT 授权测试已完成；真实 Keycloak 联测仍待完成
**日期**：2026-11-09

## 背景

阶段 1 要求服务间请求具备可验证的调用方身份和最小权限。ADR-0004 只确定用户/管理员通过 OIDC 登录，不自动建立服务身份。当前服务仍是骨架，尚无实际 Feign 客户端或内部业务端点。

## 决策

1. 阶段 1 的同步服务间 HTTP 调用使用 OAuth 2.0 Client Credentials。服务身份与用户/管理员身份分开；用户 Access Token 不作为服务调用方凭据，OIDC ID Token 不用于 API 调用。
2. 每个调用服务使用专属 client registration。接收方验证签名、issuer、有效期、面向本服务的 audience，以及获准的服务调用 scope；不同服务或权限不得共用一个宽泛的 `onecard-internal` scope。
3. Scope 采用 `onecard.<target-service>.<operation>` 命名，并由目标服务所有者维护调用方到 scope 的授权矩阵。服务调用凭据通过 Secret/环境注入，不进入代码、镜像、Nacos 配置或日志。
4. Feign 客户端启用 Spring Cloud OpenFeign OAuth2 支持，并显式配置连接/读取超时。认证服务器不可用、令牌获取失败或下游拒绝时，请求失败可见；禁止无令牌重试或伪造成功的 fallback。
5. Gateway 继续校验外部用户 Access Token。Gateway 调用服务时使用独立的 Gateway client credential；用户上下文如被业务需要，必须另行定义受信传播契约，不能把客户端提供的身份头当作已验证身份。
6. `dev` 使用阶段 0 的 Keycloak `onecard-dev` realm 作为联合测试模板；生产身份提供方、客户端登记、密钥生命周期、audience/scope 映射仍须经目标环境评审。
7. 服务仍无业务 Feign 契约，因此阶段 1 的首个认证验证使用测试专用端点/上游，不增加生产业务 API。实现覆盖合法调用、缺失/伪造 token、错误 issuer/audience、scope 不足、token endpoint 不可用和下游超时。

开发服务身份模板使用 `onecard-account-service`、`onecard-risk-service` audience，分别要求 `onecard.account.invoke`、`onecard.risk.invoke` scope。account/risk 的 `dev` profile 默认指向 `onecard-dev` realm；生产 profile 要求显式设置 issuer、JWKS URI 和目标服务 audience。真实 Keycloak client scope/audience 映射尚未配置或联测。

## 备选方案

- 服务间 mTLS：可在传输层认证工作负载，但需要证书签发、轮换和信任根运维；当前阶段暂不选为开发基线。
- 仅依赖 Nacos 注册或内网可达：无法证明调用方身份，不满足最小权限要求。
- 转发外部用户 token 作为服务身份：无法区分用户与调用服务，不采用。

## 后果

- 所有实际 Feign 客户端必须有对应的 OAuth2 client registration、目标端资源服务器校验和权限测试；不能因为当前没有调用就将未来默认设为匿名。
- transaction-service 的 dev client secret 缺省为空，不能成功向 IdP 认证；在注入有效 secret 前，实际调用保持失败关闭。生产 profile 要求显式提供 token URI、client ID 和 secret。
- account/risk 服务只允许通过各自 audience 和 scope 的 JWT 访问非 Actuator 路由；health/info/prometheus 端点保留给容器内探针和采集器。
- Gateway 与服务间身份凭据需要分开轮换；用户身份在下游的传递方式须在首个依赖用户主体的业务契约设计前确定。
- 该开发选择不构成生产身份提供方或服务授权矩阵批准。

## 来源

- [OAuth 2.0 Authorization Framework, RFC 6749](https://www.rfc-editor.org/rfc/rfc6749)：Client Credentials grant。
- [Spring Cloud OpenFeign Features](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html)：OAuth2 token interceptor 的依赖与 `spring.cloud.openfeign.oauth2.enabled` 配置。
- [ADR-0004：用户与管理员身份采用 OIDC](0004-oidc-user-and-admin-identity.md)：开发 Keycloak issuer 与用户/管理员 token 边界。
