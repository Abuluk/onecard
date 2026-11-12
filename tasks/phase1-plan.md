# 阶段 1：工程与运行平台详细开发方案

**状态**：进行中；阶段 0 临时开发基线出口已通过。平台骨架按暂定默认推进，真实渠道、OIDC 对外授权、账户资金写入、终端离线、数据库 HA 和业务容量仍受启用门禁限制。
**范围**：CI、配置与服务发现、入口与服务身份、公共响应与可观测性、迁移基础、开发/CI 运行环境
**不含**：账户/交易等业务 API、业务表结构、生产发布和生产容量承诺

本文是 `docs/开发阶段规划.md` 阶段 1 的执行细化。当前服务是可运行骨架；每个任务完成后都要留下自动化验证证据，不能以容器启动或 Actuator 返回 UP 代替验收。

## 1. 开工时基线与当前进展

- 根 Maven 工程聚合 `common`、`gateway`、八个 API 模块和八个服务模块；`.github/workflows/ci.yml` 已运行 JDK 25 的 `clean verify`，但尚未覆盖平台集成和安全门禁。
- 服务已有 `dev`、`test`、`prod` 配置。开发配置以 `optional:nacos:` 导入，测试关闭 Nacos，生产使用必需的 `nacos:` 导入；目标机已验证账户配置从 Nacos 初始导入，并通过 dev-only `@RefreshScope` 探针验证 `alpha -> beta` 在 1 秒内无重启刷新。2026-11-11 本机启动测试确认八个服务与 Gateway 的生产 profile 在 Nacos 不可达时均 fail-closed。
- transaction-service 已配置 OIDC client credentials 与 Feign 熔断/隔离基础；真实令牌和故障测试使用 test-only client 向隔离 stub 联测。account/risk 的资源服务器按 ADR-0009 校验 JWT 签名、issuer、服务 audience 和独立 scope。开发使用 Keycloak realm 模板；`apis/` 尚无 Java 服务调用契约，生产 Feign client 留待接口设计，生产身份映射仍待评审。
- Gateway 已完成开发态 OIDC JWT 验签、issuer/audience/过期校验、角色映射、路径/方法默认拒绝和共享 Valkey 限流；真实 Valkey 运行测试及 PR CI 门禁已通过，真实 Keycloak JWT 与代理/trace 联测已完成。精确生产 API 白名单仍待业务所有者批准。
- Compose 固定 Nacos 3.1.1 镜像 digest；八个服务、Gateway、Nacos、Valkey 和可选 OceanBase/RocketMQ 接入 `internal: true` 的 `onecard-private`。Gateway 单独加入 `onecard-ingress` 并只发布回环端口；监控数据库在 default 网络，Categraf 双网连接。目标机已验证九应用注册/恢复、Gateway 入口隔离和可选业务数据/消息 profile。MySQL/Redis 仍只供 N9E，业务缓存尚未接入。
- `common` 已有 `Result<T>`、业务/校验/未预期异常处理、Servlet 安全错误处理、可信 `traceparent` 响应头和平台指标白名单；Gateway 有响应式错误处理、上游 5xx 安全重写和真实 Valkey 429 限流。ADR-0010 记录当前契约。业务审计记录及业务指标仍待后续实现。
- account/transaction 已建立 Flyway 生产迁移基线和隔离 smoke 迁移目录，尚无业务 V1 表；OceanBase 双 schema 迁移/权限 smoke 已通过。k6 保留 1 VU、10 秒 Actuator 启动检查，并新增真实 OIDC Gateway-to-stub 性能场景；CI 集成门禁已覆盖 Keycloak、Valkey、Nacos 和数据库 fail-closed/恢复。
- `.github/workflows/deploy.yml` 原先包含内联发布凭据并指向不存在的构建目录；现已改为手动选择模块、使用仓库 Secret 发布。远端发布和扫描仍待验证。仓库所有者确认私人仓库并接受现有凭据处理方式，因此凭据轮换/历史处置不纳入阶段 0。
- `.github/workflows/performance-gate.yml` 原先接受任意 `base_url`；现已固定到隔离运行的本地账户服务健康冒烟，不代表平台性能验收。

## 2. 开工前置决策

下列项目由阶段 0 ADR 或临时方案给出开发默认。真实环境未确认的项目列入对应启用门禁；可以开发隔离骨架，不得开放未经验证的资金/安全行为：

| 决策 | 最小产物 | 阻断范围 |
|---|---|---|
| 目标 JDK、Spring/Alibaba 版本组合、Nacos 及中间件版本 | 兼容矩阵和可复现运行结果 | 所有配置、镜像与 CI 集成测试 |
| 用户/管理员身份源、认证协议、角色/权限声明 | OIDC、Keycloak 开发模板、`onecard-api` audience、示例角色；授权矩阵/负向测试 | 受保护 API 对外开放前完成资源服务器负向测试；未通过默认拒绝 |
| 服务间身份、设备协议入口和信任边界 | ADR-0009 OIDC client credentials、目标 audience/scope、调用身份/网络分区图与威胁模型 | 服务端口暴露与设备接入；真实 IdP 联测和调用授权矩阵未完成前不得开放业务调用 |
| Nacos namespace/group/dataId、配置密钥和启动失败策略 | 环境配置矩阵、密钥加载方式 | dev/compose/prod profile |
| 数据库/缓存/消息目标产品、版本与本地测试办法 | OceanBase MySQL + Spring JDBC + Flyway 开发默认，已有组件 PoC；应用集成/恢复测试 | 账户服务可部署前完成 JDBC/Flyway 集成和迁移对账；目标 HA 切换未测前资金写入 fail closed |
| Gateway、鉴权、配置中心和监控的阶段 SLO | 每项负载、延迟、错误率和资源阈值 | 性能门禁与阶段退出 |
| API/错误响应和 traceId 契约 | 公共响应/错误码/traceId ADR | `common` 和网关过滤器 |

在线优先、仅意外断线时进入终端故障切换的语义已由用户明确并同步到架构文档；阶段 1 只建立平台安全与可观测基础，不实现在线/离线资金流程。

## 3. 目标边界

```text
用户/管理客户端 -> Gateway 外部白名单 -> 已鉴权的用户/管理 API
支付渠道 -> 已签名回调入口 -> payment-adapter
设备 -> 独立设备协议入口/网络 -> device-service
服务间调用 -> 私有服务网络 + 已认证的服务身份
运维采集器 -> 私有管理端点（health/info/prometheus）
```

- Gateway 对外采用显式路径和方法白名单；未列入矩阵的服务、内部接口和 Actuator 一律不可由用户入口访问。
- 设备协议入口与用户 API 分开认证和隔离；需要经过 Gateway 的设备 HTTP 接口必须在阶段 0 的入口矩阵中单独批准。
- 服务端口默认只在私有网络可达。开发者直连端口只能通过显式本地调试配置开启，不作为 Compose/生产默认。
- Nacos 负责配置和服务发现，不作为用户身份、服务身份或网络隔离的替代品。
- 所有默认超时、限流、熔断、错误映射和降级均须失败可见；熔断或 fallback 不得伪造成功。

## 4. 有序任务

### 任务 0：阶段 1 启动准入核对

**目标**：核对阶段 0 临时开发基线出口，并将外部/生产证据门禁带入阶段 1。

**验收条件**：

- [x] 阶段 0 临时开发基线检查点 A-D 通过；出口评审、ADR、已有 PoC 和证据缺口/门禁均有记录。
- [x] 阶段 1 所需身份、软件组件、配置、网络、数据库开发基线和数据边界已有暂定默认；生产 OS/CPU/JDK、容量及真实合规结论列为后续门禁。
- [x] 阶段 0 凭据事项按仓库所有者已确认范围豁免；不要求轮换或历史改写，部署 workflow 工程配置仍按任务 1 修正。
- [x] 阶段 1 当前目标范围、入口默认拒绝、开发/CI 环境基线、暂行限制和退出条件已记录。

**验证**：按 `tasks/phase0-todo.md` 和阶段 0 ADR/报告逐项核验；检查任务 1-8 的阻断条件均有明确结果。

**依赖**：阶段 0 开发基线出口及退出评审。
**涉及文件**：`tasks/phase0-todo.md`、阶段 0 ADR/验证报告、`docs/开发阶段规划.md`。
**规模**：小。

### 任务 1：整理 CI 与质量门禁

**目标**：在现有 Actions 上建立每次 PR 可重复执行的构建、安全和测试门禁，不另建平行流水线。

**验收条件**：

- [ ] `ci.yml` 在 PR 和受支持分支 push 上执行 JDK 25 全量 `clean verify`，不跳过测试；Actions 权限最小化，PR job 不可访问发布 Secret。
- [ ] 按仓库策略启用分支保护，使 CI 必过；依赖漏洞检查、secret scan 和容器镜像扫描的工具与阻断级别有记录，关键/高危可达风险有处置结果。
- [ ] 部署 workflow 与测试 workflow 分离；性能 workflow 不再接受任意外部目标作为默认测试入口。
- [ ] 流水线失败时保留测试报告、启动日志和可定位的失败阶段，不以重试或关闭门禁消除失败。

**验证**：本地执行 `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`；提交临时 PR 验证成功与故意失败两种情况均正确阻断/放行；核对 PR 环境没有部署凭据。
**依赖**：任务 0 启动准入；CI 可先独立实施。
**涉及文件**：`.github/workflows/ci.yml`、`.github/workflows/deploy.yml`、安全扫描配置、`pom.xml`（仅确有构建插件需求时）、CI 说明。
**规模**：中。

**当前进度（2026-11-11）**：CI 已新增真实 Keycloak service-client 凭据测试、全部八个服务与 Gateway 在 Nacos 不可达时 fail-closed 测试、Flyway 显式开启且数据库不可达时启动失败检查、Nacos/account 重启恢复验证和失败日志上传；临时 client secret 与 Nacos token 每次运行新建并遮罩。本机九应用 Nacos fail-closed 与两服务数据库 fail-closed 脚本、全仓 19 模块 `clean verify`、CI YAML 解析和 Compose 正式/debug 配置校验通过。原镜像的四项 Alpine libexpat High 已通过固定 `expat=2.10.0-r0` 修复；Gateway 镜像经 Trivy 0.76.2 OS 扫描为 0 High/Critical。剩余 Nacos client 3.1.1 shaded Guava 33.3.1-android 仍受 CVE-2026-102554 影响；已核对 Maven Central 最新 client 3.3.0 仍内嵌易受影响的 33.5.0-android，尚无已验证修复版，未豁免。GitHub CLI 不在当前工作站，分支保护、Dependabot alerts、远端 workflow 结果及剩余 High 处置仍未验证，任务保持进行中。

### 任务 2：统一 profile、Nacos 配置导入和本地平台栈

**目标**：让配置加载策略明确可测，并使开发者可用与目标产品一致的本地/CI 依赖启动平台。

**当前进度（2026-11-11）**：默认、debug、business-data、business-messaging Compose 配置校验通过并纳入 CI。目标机 `admin1@192.168.0.41` 的九个服务应用 + Gateway 均健康并在 Nacos 3.1.1 注册，Valkey 健康，Gateway 管理健康为 UP，匿名 API 返回标准 401。`nacos-config-refresh-smoke.ps1` 可重复验证 account 的 Nacos 初始导入和动态刷新：值从 alpha 更新到 beta，1 秒内生效且服务未重启；dev 配置探针默认关闭、test profile 请求返回 401。`prod-config-fail-closed-smoke.ps1` 已真实启动全部八个服务和 Gateway 的 `prod` profile 并指向无监听的临时 Nacos 端口，九者均在 60 秒内非零退出并记录 Nacos 错误；CI 门禁同步扩展为九应用。OceanBase CE 4.4.2-lts 与 RocketMQ 5.3.1 在最新私网拓扑组合 smoke 通过，OceanBase `SELECT 1` 返回 1。生产完整配置矩阵、namespace/认证策略及所有服务的配置变更范围仍待评审。

**验收条件**：

- [ ] 八个服务和 Gateway 的应用名、端口、profile、Nacos namespace/group/dataId 与密钥入口遵循批准的环境矩阵；采用 `spring.config.import`，不混用旧式 bootstrap 配置。
- [ ] `test` profile 不偷偷依赖真实 Nacos；`compose`/生产需要的配置缺失或 Nacos 不可达时按 ADR 失败启动；允许开发默认值的 profile 明确标注且不包含密钥、真实账号、支付策略或安全阈值。
- [ ] 本地 Compose 增加阶段 0 选定且精确固定版本的 Nacos 与业务中间件；N9E 专用 MySQL/Redis 与业务数据组件分离，服务注册/发现通过 Nacos 验证。
- [ ] `.env.example` 仅含占位项；Compose 配置检查通过，所有关键依赖有健康检查、启动依赖和清晰的停止/清理说明。
- [ ] 配置更新行为有约束：动态刷新只用于批准的可热更新配置；密钥、端口、线程池等需重启的项目有明确处理方式。

**验证**：`docker compose -f deploy/monitoring/docker-compose.yml config --quiet`；测试 Nacos 可达/不可达、配置存在/缺失、非法格式、配置更新、服务注册/注销和重连；检查完整启动日志不含密钥。使用批准的目标产品验证，不用 N9E MySQL/Redis 代替业务环境。

**依赖**：任务 0 的组件/配置 ADR。
**涉及文件**：各服务和 Gateway 的 `application*.yml`、`deploy/monitoring/docker-compose.yml` 或选定的唯一开发 Compose、`.env.example`、`.gitignore`、Compose/配置说明。
**规模**：大；按 Gateway、业务服务 profile、Compose/文档三个小 PR 执行，每批均跑配置测试。

### 任务 3：Gateway 外部路由白名单与用户/管理员鉴权

**目标**：建立可审计的外部 API 入口，默认拒绝未批准路径。

**当前进度（2026-11-11）**：Gateway 在身份验证后按 JWT `sub` 经 Valkey Lua 原子滚动限流，GET 60 次/60 秒、POST 10 次/60 秒；真 Valkey HTTP 测试覆盖上下限、主体隔离、统一 429 以及不可用 fail-closed 503，并已加入 PR CI。另在 `admin1@192.168.0.41` 通过真实 Keycloak 26.8.1 token 验证签名、issuer、audience、角色映射、匿名 401、stub 代理和 W3C trace；30 秒 3,200/min 受控基准通过 3,202/3,202 功能检查。域级路径仍不是生产 API 批准，精确 API/所有者/生产阈值矩阵尚未完成。

**验收条件**：

- [ ] 每个外部 API 路由都有所有者、允许的路径/HTTP 方法、身份要求、角色/权限和速率策略；用户、管理、支付回调、设备协议、内部调用分开列明。
- [ ] 路由由清单显式定义；默认拒绝未配置路由，不提供面向用户的 `/api/risk/**`、`/api/devices/**` 或通用服务代理路径，除非 ADR 明确批准专用入口。
- [ ] Gateway 使用阶段 0 选定的认证机制验证身份和权限；移除/覆盖客户端伪造的身份头，未授权、过期、错误受众或权限不足均返回规定的统一错误。
- [ ] CORS 仅在存在浏览器调用需求时配置明确来源；认证端点和高风险接口的限流使用可跨实例共享的策略，不依赖单实例内存计数。

**验证**：自动化覆盖匿名、有效用户、权限不足管理员、有效管理员、过期/伪造凭据、错误方法、未知路径、路径编码/前缀绕过、客户端伪造身份头和限流边界；通过 Gateway 访问 Actuator、风控内部路径和服务管理端点必须失败。

**依赖**：任务 0 的身份/路由 ADR；任务 2 的运行配置。
**涉及文件**：`gateway/pom.xml`、Gateway 路由与安全配置、Gateway 测试、API 暴露矩阵 ADR。
**规模**：大；先形成路由矩阵，再按用户/管理路由和回调/设备入口分批实施。

### 任务 4：服务身份、网络分区和下游调用韧性

**目标**：阻止绕过 Gateway 直连服务，并约束服务间调用和依赖故障。阶段 1 服务间 OAuth2 client credentials 开发基线见 [ADR-0009](../docs/adr/0009-oidc-service-to-service-identity.md)。

**当前进度（2026-11-11）**：transaction-service 已配置 OIDC client credentials、超时和 Spring Cloud CircuitBreaker/Resilience4j；真实 Keycloak 与故障注入使用 test-only Feign probe 将 token 送达隔离 stub，并验证签名、issuer、account audience、`onecard.account.invoke` scope、`traceparent`、熔断和并发拒绝。真实 account 服务对有 scope token 返回 200、缺 scope 返回 403。熔断器按 Feign 目标共享，最小采样 5、失败率 50% 开路、等待 2 秒后允许 2 次半开探测；Semaphore bulkhead 限制每目标并发 32，饱和零等待拒绝。HTTP 503、读取超时与断路/并发拒绝统一返回安全的 503/504 结果，不伪造成功且不重试。集成测试覆盖上述行为及实际 HTTP 错误映射，8 项通过。`apis/` 尚无 Java 服务调用契约，生产源码没有 Feign client；因此 OIDC/熔断配置的实测是框架基线验证，不能视为生产业务调用已接通。CI 每个 PR 启动隔离 Keycloak 跑真实 token 测试。Compose 默认业务应用使用内部网络，Gateway 仅经额外 ingress bridge 发布 `127.0.0.1:8081`；目标机实测外部工作站不能直连该端口。SSH 恢复后目标机完整 `clean verify`、九应用构建、全栈停启/Nacos 重启后 9/9 注册恢复、account 重启和匿名 401 均通过，最新证据 `artifacts/platform-recovery-03734da9e8/report.md`；业务 profile 组合 smoke 通过。生产调用方授权矩阵、业务 API 契约、生产网络策略及其他链路的故障注入仍未验收。

**验收条件**：

- [ ] 内部服务使用 ADR-0009 的 OIDC client credentials；认证主体、audience 和 scope 按调用方/目标服务最小授权，不能仅凭 Nacos 注册或客户端自报头信任。
- [ ] Compose/部署默认不把业务服务和管理端口映射到外部宿主网络；监控采集器只在私有网络访问 Prometheus 端点；本地直连须通过显式 debug profile 开启。
- [ ] 所有已配置的服务调用和 Gateway 路由显式设连接/响应超时；熔断、限流、并发隔离与 fallback 策略有资源上限和失败语义。
- [ ] 设备协议和管理入口不因服务端口可达而自动成为 Gateway 用户入口；管理和运维访问有独立边界及审计。

**验证**：从非授权容器/主机验证服务端口不可达；验证合法服务主体可调用、伪造主体和无权服务调用被拒绝；注入下游延迟/超时/断连，确认请求在批准时限内结束、线程/连接不无限堆积、fallback 不返回成功。

**依赖**：任务 0 身份/网络 ADR；任务 2、3。
**涉及文件**：服务安全配置、Gateway/Feign 调用配置、Compose 网络/端口、服务调用测试。
**规模**：大；先打通 `transaction -> account/risk` 与 `access -> device` 骨干，再覆盖其余服务。

### 任务 5：统一错误、traceId、审计日志与业务指标

**目标**：为后续业务切片提供一致且不泄密的诊断接口。

**当前进度（2026-11-11）**：ADR-0010 已记录 `Result<T>` 错误码和 W3C `traceparent` 契约。account/risk/Gateway 已实现并测试 400/401/403/500 与连接失败/超时异常映射，安全拒绝日志关联 trace ID，Prometheus 只注册低基数平台指标。Gateway 的 500/503/504 上游响应重写和 Valkey 共享限流已通过真实 HTTP 测试；Valkey 断连 fail-closed 返回 503。资金/管理操作持久审计与业务指标仍需等业务 API 和审计/保留策略定义后实现，因此任务未完成。

**验收条件**：

- [ ] 按批准契约统一业务错误、参数错误、认证/授权、限流、下游不可用和未预期错误的 HTTP 状态、错误码及 `Result` 响应；通用异常不泄露堆栈、SQL、内部地址或凭据。
- [ ] 外部请求入口建立可信 traceId；不可信入站头经格式校验/替换，Gateway 到服务的 traceId 与 MDC/日志一致，响应头按契约回传。
- [ ] 关键安全事件和管理操作可审计；日志不包含口令、token、完整卡号/个人敏感信息。日志字段、采样和保留按阶段 0 数据分类决策执行。
- [ ] Micrometer/Actuator 暴露仅包含批准指标；高基数字段、个人信息和凭据不得作为 metric label。

**验证**：为 `common` 全局异常处理增加正常、验证失败、认证失败、未知异常和敏感数据断言；端到端核对请求、响应、日志和指标中的 traceId；扫描日志/Prometheus 输出中的敏感字段。改公共模块后跑全量 Maven 验证。

**依赖**：任务 0 错误/trace/数据分类 ADR；Gateway 主链路完成。
**涉及文件**：`common` 响应和异常处理、Gateway 过滤器、Logback/Actuator 配置、单元与集成测试。
**规模**：中。

### 任务 6：数据源权限和 Flyway 迁移基线

**目标**：建立后续业务建模可复用的数据库所有权和迁移机制，不提前创建业务表。

**验收条件**：

- [ ] 依据数据责任矩阵为数据持有服务分配独立 schema/database 身份；服务账号不能读写其他服务 schema，Gateway 不持有业务数据库凭据。
- [ ] Flyway 版本、命名、目录、校验和失败处置规则固定；实际服务迁移脚本进入 `sql/<service>/` 或 ADR 规定的唯一位置，禁止手工修改环境数据库。
- [ ] 新环境可从空 schema 执行 migration smoke；升级路径可重复执行并检测脚本篡改；没有业务表的服务不为“统一”而安装无用数据访问依赖。
- [ ] 密码通过 Secret/环境注入；迁移用户与运行用户权限分离，迁移和应用启动日志不泄漏连接串密码。

**验证**：目标数据库验证空库初始化、上版升级、失败迁移中断/恢复、重复启动、校验和错误、最小权限和跨 schema 拒绝；失败恢复按 ADR 执行，不假定所有数据库都支持自动回滚。

**依赖**：任务 0 数据库/数据责任 ADR；任务 2 目标 DB 可用。
**涉及文件**：相关服务 POM 与数据源配置、`sql/` 迁移目录、Compose 初始化、迁移集成测试。
**规模**：中至大；按服务所有权分批启用。

**当前进度（2026-11-11）**：已新增 ADR-0011；首批账户与交易服务各自加入 Spring JDBC/MySQL Connector/J/Flyway，分别配置 `onecard_account` 与 `onecard_transaction`，运行账号和迁移账号分离，生产 profile 强制凭据并启用 Flyway，默认开发/Compose 数据库开关关闭。两服务都固定生产迁移目录与 V1 smoke 专用测试目录；测试覆盖首次/重复迁移、checksum 篡改拒绝、应用账号无 DDL 权限及应用/迁移账号跨服务 schema 拒绝。另在本机显式启用 Flyway、关闭 Nacos 并让 JDBC 端口不可达，account 与 transaction 均按预期因迁移/连接失败而退出；CI 已增加相同 fail-closed 检查。`.env.example`、Compose 环境变量和一次性 OceanBase smoke 脚本已落地。2026-11-11 在指定 `admin1@192.168.0.41` 上用 digest 固定的 OceanBase CE 4.4.2-lts 完成双 schema 真实 smoke：account 和 transaction 各自通过首次/重复迁移、checksum 篡改拒绝、运行账号 DDL 拒绝、失败迁移保留 `FAILED` 状态并经 Flyway `repair()` 后重跑成功，另有 2 条验证双方运行/迁移账号跨服务 schema 拒绝，共 10 tests 全通过；更新后的 `oceanbase-account-smoke.ps1` 复跑通过，保存测试日志并扫描生成密码/token 未发现泄露，临时容器、Docker context 与 SSH 隧道已清理。payment-adapter/clearing 的持久化责任仍待业务数据矩阵确认，因此任务未关闭。

### 任务 7：本地开发入口和平台集成测试

**目标**：让新开发者与 CI 使用同一份可复现环境和冒烟流程。

**验收条件**：

- [ ] 文档说明 JDK/Maven/Docker 前置条件、单测/Compose/完整集成测试命令、服务地址、Nacos 首次配置、清理与故障排查；不同 README/script 不给出冲突指令。
- [ ] 本地 CI 不只启动账户服务健康检查；增加网关代理测试、配置/发现、鉴权/路由边界、Actuator 私有化和数据库 migration smoke。
- [ ] 关键平台集成测试在 PR 门禁执行；重型监控或持续压测放手动/定时 workflow，测试数据创建/清理可重复。
- [ ] Compose 默认只发布经批准的 Gateway/监控 UI 端口；N9E 与 GreptimeDB 的本地访问仍按开发说明绑定回环地址。

**验证**：全新 clone 按文档从零启动并执行测试；停止/重启 Nacos、业务数据库和一个服务后重跑，检查自动恢复和测试退出码；校验容器日志、Compose 健康状态和 CI 产物。

**依赖**：任务 1-6 对应能力可集成；目标产品测试环境就绪。
**涉及文件**：`ci-local.ps1`、必要的 `scripts/`、Compose、`.github/workflows/ci.yml`、`README.md`、`docs/ops/`。
**规模**：中。

**当前进度（2026-11-11）**：文档和本地脚本记录 JDK/Maven/Docker/k6、Keycloak/OIDC、Nacos token、DataId、服务地址与资源清理。本机隔离栈验证 Nacos/account 健康、注册、重启恢复和 k6 20/20 checks；目标开发机上九服务 + Gateway + Valkey 全部健康、九实例注册，Gateway 返回匿名 401；配置刷新脚本真实发布两版 Nacos 配置并验证无重启更新。SSH 恢复后目标机完整 `clean verify`、九应用镜像构建、Gateway loopback 映射和外部不可达、全栈停启/Nacos 重启后 9/9 注册恢复、account 重启及匿名 401 全通过，最新证据 `artifacts/platform-recovery-03734da9e8/report.md`；OceanBase/RocketMQ 组合 profile smoke 通过。CI YAML、Compose 正式/debug/可选 profile 配置和全仓构建通过。远端 `main` 最新读取为 `0074cc8a969cc266c744b33013fcec8d47973b72`；精确查询 `feature/project-architecture` 未返回远端引用，因此当前改动的远端 Actions 结果仍未验证。OceanBase PR workflow 仍待验证。

### 任务 8：平台性能、压力和故障恢复验收

**目标**：测量平台基础设施本身的开销与恢复行为，不用 Actuator 健康检查冒充业务性能。

**当前进度（2026-11-11）**：新增隔离的 Keycloak 26.8.1 + Gateway + account + Python stub 场景及可重复 k6/资源/Prometheus 报告。最终目标机四阶段运行合计 105,922 请求，317,766/317,766 功能检查通过，全部 HTTP 200、错误率 0%；500 VU/30 分钟阶段完成 96,303 请求，p50/p95/p99=1.7/2.3/3.5ms。报告含约 5 秒间隔容器 CPU/内存采样峰值、JVM heap/GC、executor、HTTP 状态和凭据日志扫描，数据库连接池标注为不适用；临时容器、目录和 SSH 隧道已清理。脚本现按阶段持续时间强制最低请求数，并以 3,210/min 留出调度余量。另在本机 account/transaction 验证 Flyway 开启时数据库不可用 fail-closed；真实 Valkey 不可用、Nacos 配置刷新/重启、下游 503/超时和服务重启已有测试或实测。当前没有业务 RocketMQ 消费者，消息依赖故障注入尚无法形成业务降级验收。阶段 0 已将 3,200/min、30 分钟、96,000 请求定为开发容量压力输入；生产 SLO 明确延期，当前只收集开发基线、不声明生产 SLO 达标。

**验收条件**：

- [ ] 建立仅用于测试的 stub upstream/身份服务场景，经 Gateway 验证代理、认证和 trace 开销；测试入口在生产配置中不存在。
- [ ] 先在本地、后在 16 GB 开发服务器按当前可用负载模型执行基准、阶段性并发、压力和恢复测试；测试报告包含环境、版本、数据量、并发、吞吐、p50/p95/p99、错误率、CPU/内存/GC、连接池/线程池，并明确结果仅代表开发环境。
- [ ] 注入 Nacos/数据库/缓存/消息依赖不可用、配置刷新、下游慢响应、连接重建和服务重启；确认系统在批准时限内降级或恢复，无无限重试、无限队列/连接增长或未经授权的 fail-open。
- [ ] Gateway、认证、服务调用、注册/配置和监控采集达到阶段 0 SLO；若未通过，记录瓶颈及修复后的复测结果，不下调阈值规避失败。

**验证**：新增 k6 平台脚本以 `BASE_URL` 指定本地受控环境，SLO 从批准的配置/阶段报告读取；运行标准负载和故障恢复脚本，归档原始结果和资源指标。旧 `smoke.js` 保留为启动可用性检查并清楚标注，不作为性能通过证据。

**依赖**：任务 0 负载/SLO ADR；任务 2-7。
**涉及文件**：`tests/performance/`、`.github/workflows/performance-gate.yml`、测试 stub/Compose 配置、性能报告。
**规模**：中；基准、故障恢复和长稳分开执行。

## 5. 检查点与阶段退出

### 检查点 A：入口和供应链安全（任务 0-3）

- [ ] 凭据已吊销/轮换，工作树和历史检查有记录；发布 workflow 不会在普通 push 上自动推送。
- [ ] CI 必须通过；配置 profile 矩阵和 Gateway 暴露矩阵已评审。
- [ ] 公众入口默认拒绝未知路由；设备、管理和内部入口不能由用户路由绕过。

### 检查点 B：运行平台基础（任务 4-7）

- [ ] 服务身份、超时、限流/熔断和网络隔离有自动化验证。
- [ ] 统一错误、traceId、审计、指标和管理端点访问策略通过测试。
- [ ] 目标数据库迁移/权限与本地/CI 集成环境可重复运行。

### 检查点 C：性能和恢复（任务 8）

- [ ] 平台性能报告按阶段 0 SLO 通过，附环境和复现命令。
- [ ] 依赖中断、恢复和服务重启测试通过；无开放的阻断级安全缺陷。

**阶段 1 退出条件**：上述检查点全部通过；全量 `clean verify` 与平台集成门禁进入 CI；服务 API/管理端点/设备入口的网络和身份边界有可复测证据；迁移、安全扫描、日志和告警可在当前开发基线验证；阶段 0 批准的开发环境门槛达标；运维和开发文档已同步。生产 OS/CPU/JDK 发行版适配、生产 SLO 和更大规模容量在试点/生产前重新验证。未达到阶段 1 开发退出条件时不得开始阶段 2。

## 6. 风险与限制

| 风险 | 影响 | 控制方式 |
|---|---|---|
| 阶段 0 身份源或目标产品未定 | 鉴权、配置、迁移可能返工 | 先完成 ADR；相关任务标记阻断，不提交临时生产机制 |
| 私人仓库现有凭据处理方式 | 仓库所有者认为当前方式可接受 | 按所有者意见不做事件处置；如仓库可见性、权限或所有者决定变化，重新评估 |
| 生产 OS/CPU/JDK 发行版暂不可用 | 生产联合兼容无法提前确认 | 当前开发基线先推进；生产部署前取得目标环境并完成适配验证 |
| 16 GB 开发服务器被误作生产容量证明 | 线上容量和成本判断偏差 | 报告标注开发环境；生产资源更换后按业务负载重测 |
| 业务服务同时监听宿主端口 | 绕过 Gateway 鉴权/限流 | 默认只暴露 Gateway，直连只准显式本地调试 profile |
| 用 N9E 的 MySQL/Redis 承载业务数据 | 账号、数据与恢复边界混淆 | Compose 分离命名、存储卷、凭据和健康检查 |
| 多项平台能力同时铺到八个服务 | 变更面大、回归难 | 先 Gateway/两个真实调用边，再按服务批次滚动覆盖，每批跑全量验证 |
| 用 Actuator 压测替代服务链路 | 得出错误容量结论 | 用仅测试 stub upstream 验 Gateway/认证开销，负载按阶段 0 批准 |

## 7. 执行说明

实现期间以 `tasks/phase1-todo.md` 记录任务状态；每个检查点提交测试报告和决策更新。一次只完成一个垂直切片，切片通过后再进入依赖它的任务。若实际依赖/版本和本方案不符，先更新 ADR 与本方案，再改实现。

## 最新复核（2026-11-12）

新增 transaction Feign 熔断和并发隔离后，定向集成测试 8/8 通过，MockMvc 验证断路、503 和 504 的安全响应；随后在 `admin1@192.168.0.41` 再次执行全仓 `clean verify`、九应用镜像构建与恢复 smoke，均通过。九应用全栈停启及 Nacos/account 重启后注册恢复 9/9，Gateway loopback 边界与匿名 401 正常。最新报告：`artifacts/platform-recovery-03734da9e8/report.md`；脚本已清理临时容器、镜像、volume、网络、Docker context 和 SSH 隧道。

transaction Resilience4j 改动后再跑真实 Keycloak 26.8.1 OIDC smoke：client-credentials transaction 集成测试 1/1 通过，account scope 正向/负向分别返回 200/403；四阶段 1 分钟负载合计 12,835 请求、38,505/38,505 checks、0% HTTP 错误，日志凭据扫描无泄露。证据：`artifacts/phase1-platform-1829d6d7f86b/report.md`；临时容器、Docker context 和 SSH 隧道已清理。

本轮曾通过已有凭据只读读取私有仓库：远端 `main` 为 `0074cc8a969cc266c744b33013fcec8d47973b72`，该提交的 Actions CI/deploy 成功。随后查询本地分支远端引用遇 DNS 失败；本地 `feature/project-architecture` HEAD `79a0b16` 相对 `origin/main` 跟踪引用领先 17 个提交，因此当前改动的远端 PR/CI 未验证。branch protection 与 Dependabot alerts API 返回 403。新增的 performance profile 解析回归测试复现并修复了重复 YAML `security` 键；真实 Keycloak 四阶段 smoke 和全仓 19 模块 `clean verify` 通过。此前一次 500 VU/30 分钟运行达到 95,995 请求，距 96,000 目标差 5；k6 已加入最低请求数门槛并以 3,210/min 节奏留出调度余量，后续完整复测达到目标。业务 API/调用者批准矩阵、正式配置与数据责任决策、持久审计保留策略、GitHub 管理权限及生产 SLO 仍是阶段 1 退出门槛。

最终复测已完成：修复后的 500 VU/30 分钟阶段完成 96,303 请求，四阶段合计 105,922 请求、317,766/317,766 checks、0% HTTP 错误，证据为 `artifacts/phase1-platform-e9ed7f3f70f8/report.md`。SSH 恢复后再次运行目标机恢复 smoke，完整 `clean verify`、九应用构建与启动、整栈停启、Nacos/account 重启、9/9 注册和匿名 401 均通过，证据为 `artifacts/platform-recovery-e475fa6375/report.md`；临时容器、镜像、volume、Docker context 与 SSH 隧道已清理。生产 API/调用者批准矩阵、正式配置与数据责任决策、持久审计保留策略、GitHub 管理权限及生产 SLO 仍未获批准或验证。

SSH 恢复后的最新目标机复测报告为 `artifacts/platform-recovery-03734da9e8/report.md`；本机全仓 `clean verify` 为 19/19 模块成功，Surefire 共 77 项测试、0 失败/错误、16 项外部服务 profile 测试跳过；Keycloak、Valkey、OceanBase 外部集成另有真实服务 smoke 通过。远端 `main` 最新查询仍为 `0074cc8a969cc266c744b33013fcec8d47973b72`；精确查询 `feature/project-architecture` 未返回远端引用，本地改动尚无远端 PR/Actions 结果。`gh` CLI 未安装，branch protection 与 Dependabot alerts API 仍需管理权限验证。NVD 当前记录确认 CVE-2026-102554 影响 Guava `<33.7.2`，修复版为 33.7.2；Maven Central 当前最新 `nacos-client` 为 3.3.0，但此前镜像扫描显示其内嵌 Guava 33.5.0-android，尚无可验证修复版，不降 Nacos server 基线、不豁免该 High。

范围决策（2026-11-11）：用户确认阶段 1 按阶段 0 临时开发出口验收；业务路由保持默认拒绝，生产身份/调用者矩阵、持久审计保留策略和 payment-adapter/clearing 数据责任继续作为生产启用门禁。退出评审仍未通过：当前 Nacos client High 未解决，当前 transaction 镜像扫描尚未完成，当前本地分支没有可核验的远端 CI 结果。

本轮通过 `docker.1panel.live` 镜像源下载 Trivy 数据库并扫描最新 transaction 镜像：Alpine 3.24.4 为 0 High/Critical，应用 JAR 为 1 High/0 Critical，命中 `nacos-client:3.1.1` 内嵌 Guava 33.3.1-android 的 CVE-2026-102554；报告和原始 JSON 位于 `artifacts/trivy-transaction-20261112/`。Maven Central 的 `nacos-client:3.1.2` 和最新 `3.3.0` 都内嵌 Guava 33.5.0-android，尚未达到修复版 33.7.2，不能作为修复。扫描门禁仍阻断，不添加豁免，Nacos server 3.1.1 基线不变。

2026-11-12 在 `admin1@192.168.0.41` 按当前本机 JAR no-cache 重建九个应用镜像，逐一比对镜像内 JAR 和本机 SHA-256（9/9 一致）。Trivy 0.76.2 使用 2026-11-12 更新的漏洞库与 Java 库扫描九镜像：每个 OS 0 High/Critical、Java 1 High/0 Critical，全部命中 `nacos-client-3.1.1.jar` 内的 Guava 33.3.1-android / CVE-2026-102554。完整 digest、JAR 哈希和原始 JSON 见 `artifacts/trivy-all-20261112/`。首轮冷缓存扫描触发默认 5 分钟扫描 timeout；复用已下载数据库后以 30 分钟 timeout 完成，且验证 `TRIVY_TIMEOUT` 环境变量可控制 CLI，已将 CI 扫描 action 设置为 30 分钟。扫描门禁仍失败，无豁免；Nacos server 保持 3.1.1。

为补齐任务 8 可执行的消息基础设施故障恢复验证，`scripts/business-profiles-smoke.ps1` 新增 RocketMQ broker stop/start 检查：必须观察到 broker `exited`，并在 180 秒内恢复 `running + healthy`，同时确认 nameserver healthy。目标机 `business-data + business-messaging` 组合 smoke 通过：OceanBase `SELECT 1` 一次返回 1，RocketMQ broker 在 6 秒内恢复 healthy；报告 `artifacts/business-profiles-d2a1fd8b2be5/report.md`。生产业务尚无 RocketMQ producer/consumer，因此报告仅证明 broker 进程恢复、不证明消息投递或业务降级。

2026-11-12 Nacos Pure 修复后续：此前 Trivy 命中的是九个应用包中的 `nacos-client-3.1.1` shaded Guava，属于修复前历史扫描。当前九个应用包均已核验为 `nacos-client-3.3.0-pure` + `guava-33.7.2-jre`，没有旧 client 3.1.1 或普通 shaded client 3.3.0。Compose 中 Nacos Server 镜像仍是 3.1.1；它不是 Maven `nacos-client`，`pure` classifier 不适用于 Server。全仓 `clean verify` 19/19、Gateway 集成测试 21/21 及本地 Server 3.1.1 注册/恢复/配置刷新验证通过。任务 1 按用户标准标记完成，当前分支远端 CI/CD 结果未核验且不阻塞。
