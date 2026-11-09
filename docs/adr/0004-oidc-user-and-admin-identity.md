# ADR-0004：用户与管理员身份采用 OIDC

**状态**：OIDC 已选；Gateway 开发资源服务器与负向集成测试已完成；真实 Keycloak 联测及生产授权矩阵仍待完成
**日期**：2026-11-04

## 背景

用户端和管理端需要统一身份认证，Gateway 需按身份及业务资源执行授权。此前身份提供方和角色声明未确定。项目尚无认证实现；当前路由配置不代表已鉴权。

## 决策

1. 用户与管理员身份集成采用 OpenID Connect（OIDC），Gateway 作为 OAuth 2.0 JWT 资源服务器只接受 Access Token。
2. `https://xxx` 已确认是脱敏占位符，不是实际 issuer。开发使用 Keycloak 模板 issuer `http://keycloak:8080/realms/onecard-dev`；正式身份提供方仍待选定，开发 issuer 不作为正式环境地址。
3. 角色声明采用 Keycloak 默认 realm role 路径 `realm_access.roles`，PoC 已验证为字符串数组并包含分配的 realm role。开发模板角色暂定为 `onecard-user`、`onecard-admin`、`onecard-auditor`；生产角色值及用户/管理员权限边界仍待校方授权矩阵确认。
4. API 接受 OAuth 2.0 Access Token，不接受把 OIDC ID Token 当作 API 凭据。Gateway 通过签名密钥、issuer、audience 和令牌时间声明校验 JWT。
5. 开发 audience 为 `onecard-api`；开发 issuer、audience、JWKS URI 和角色名由 `dev` profile 提供示例默认并允许环境覆盖，生产 profile 要求显式注入。PoC 默认 audience `account` 不用于一卡通 API。
6. 本 ADR 仅覆盖用户和管理员身份。服务间调用、设备/控制器身份及支付渠道回调验签须分别定义，不因 OIDC 选择而默认解决。
7. 实施时必须校验受信任签名密钥、issuer、audience、有效期和令牌用途；授权必须采用批准的角色值及资源规则。密钥轮换、身份提供方不可用和撤权传播行为须有测试。
8. 阶段 0/1 开发验证模板为 `fullScopeAllowed=false` 和 `ENFORCING`，客户端只显式授予 API 所需角色；Gateway 集成测试覆盖无效签名、issuer 不匹配、audience 不匹配、令牌过期和角色不足，预期前四项 401、角色不足 403。测试使用进程内临时 RSA/JWKS endpoint；Keycloak 客户端导出、完整授权矩阵和真实 IdP 联测仍待完成，不替代正式 IdP 生产配置。

## 备选方案

- 由本系统自行管理用户凭据：需要本系统承担凭据生命周期、恢复和安全控制；本次未选择。
- 接入现有 OIDC 身份提供方：本次选择，具体提供方绑定待确认。

## 后果

- 可把认证交给外部身份提供方，但运行配置依赖其 discovery/JWKS 可达性、令牌签发及密钥轮换。
- 开发资源服务器验证和默认拒绝策略已有自动化覆盖；生产 issuer、角色值和权限映射尚未补齐，正式 Gateway 绑定仍受启用门禁限制。
- 后续认证测试需覆盖无效签名、错误 issuer/audience、过期/未生效令牌、角色缺失/格式错误、未授权资源、撤权及身份提供方故障。

## 复审条件

选定正式身份提供方并取得其 discovery metadata、API audience、客户端/登录流程、Access Token 用法、批准角色值、用户及管理员权限矩阵后，补充实施 ADR 或修订本 ADR，并完成正反向授权测试。

开发态 Gateway 路径与方法授权矩阵见[阶段 1 Gateway 入口白名单](../阶段1-Gateway入口白名单.md)。

## 来源

- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)：OIDC 身份层及 ID Token 语义。
- [Spring Security OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)：Spring Security JWT resource server 配置与校验能力。
- [Keycloak 容器运行文档](https://www.keycloak.org/server/containers)：临时开发容器启动方式；PoC 使用 `start-dev`，不代表生产部署配置。
- [Keycloak 26.8.1 release](https://www.keycloak.org/2026/10/keycloak-2681-released)：PoC 镜像版本来源。
