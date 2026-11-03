# ADR-0004：用户与管理员身份采用 OIDC

**状态**：OIDC 已选；Keycloak claim 结构 PoC 通过；正式身份提供方、audience 和授权映射待补齐
**日期**：2026-11-03

## 背景

用户端和管理端需要统一身份认证，Gateway 需按身份及业务资源执行授权。此前身份提供方和角色声明未确定。项目尚无认证实现；当前路由配置不代表已鉴权。

## 决策

1. 用户与管理员身份集成采用 OpenID Connect（OIDC）。
2. `https://xxx` 已确认是脱敏占位符，不是实际 issuer。正式身份提供方尚未选定；隔离 Keycloak PoC issuer 不作为正式环境地址。
3. 角色声明采用 Keycloak 默认 realm role 路径 `realm_access.roles`，PoC 已验证为字符串数组并包含分配的 realm role。生产角色值及用户/管理员权限边界仍待确定。
4. API 接受的凭据类型尚未确定。OIDC ID Token 与 API 使用的 OAuth 2.0 Access Token 不可在未决策前混为一谈。
5. API audience、登录/客户端注册方式、注销与撤权时限尚未提供，按 TBD 留待身份方案确认。PoC 的默认 audience 为 `account`，不得据此配置一卡通 API；也不得仅凭 claim 路径开启 Gateway 认证或授权。
6. 本 ADR 仅覆盖用户和管理员身份。服务间调用、设备/控制器身份及支付渠道回调验签须分别定义，不因 OIDC 选择而默认解决。
7. 实施时必须校验受信任签名密钥、issuer、audience、有效期和令牌用途；授权必须采用批准的角色值及资源规则。密钥轮换、身份提供方不可用和撤权传播行为须有测试。

## 备选方案

- 由本系统自行管理用户凭据：需要本系统承担凭据生命周期、恢复和安全控制；本次未选择。
- 接入现有 OIDC 身份提供方：本次选择，具体提供方绑定待确认。

## 后果

- 可把认证交给外部身份提供方，但运行配置依赖其 discovery/JWKS 可达性、令牌签发及密钥轮换。
- 当前没有可配置的正式 issuer；audience、角色值和权限映射补齐前，阶段 0 身份/授权边界未完成，依赖它的 Gateway 鉴权实现保持阻断。
- 后续认证测试需覆盖无效签名、错误 issuer/audience、过期/未生效令牌、角色缺失/格式错误、未授权资源、撤权及身份提供方故障。

## 复审条件

选定正式身份提供方并取得其 discovery metadata、API audience、客户端/登录流程、Access Token 用法、批准角色值、用户及管理员权限矩阵后，补充实施 ADR 或修订本 ADR，并完成正反向授权测试。

## 来源

- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)：OIDC 身份层及 ID Token 语义。
- [Spring Security OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)：Spring Security JWT resource server 配置与校验能力。
- [Keycloak 容器运行文档](https://www.keycloak.org/server/containers)：临时开发容器启动方式；PoC 使用 `start-dev`，不代表生产部署配置。
- [Keycloak 26.8.1 release](https://www.keycloak.org/2026/10/keycloak-2681-released)：PoC 镜像版本来源。
