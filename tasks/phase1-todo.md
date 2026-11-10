# 阶段 1 任务清单

本清单仅跟踪阶段 1。阶段 0 临时开发基线出口已通过，阶段 1 可按暂定默认开展平台骨架和隔离开发。渠道实收、受保护 API 对外开放、账户资金写 API、终端离线及生产 HA 自动切换仍须先通过[阶段 0/1 启用门禁](../docs/阶段0-出口评审.md)。生产 OS/CPU/JDK 发行版适配及生产容量测试继续延期。

- [x] 任务 0：阶段 1 启动准入核对（阶段 0 临时出口通过；阶段 1 依赖的开发默认和 fail-closed 门禁已记录）
- [ ] 任务 1：整理 CI 与质量门禁（进行中；PR/push 全量 verify、只读权限、失败报告留存、Compose 配置校验、Dependabot 更新 PR、依赖审查、全历史 Gitleaks secret scan（仅精确忽略两条已接受的历史指纹）、九个应用镜像 Trivy HIGH/CRITICAL 阻断、手动发布和固定本地冒烟入口已配置；本机 19 模块 clean verify 通过。2026-11-10 重建的九个镜像均为 0 Critical、5 High：Nacos 客户端 shaded Guava 33.3.1-android（CVE-2026-102554）和 Alpine libexpat 2.9.0-r0（CVE-2026-113351、CVE-2026-115506、CVE-2026-117610、CVE-2026-118370），当前未静默豁免；GitHub 分支保护、Dependabot alerts 和远端 workflow 结果尚待配置/验证）
- [ ] 任务 2：统一 profile、Nacos 配置导入和本地平台栈（进行中；Compose 继续固定已验证的 Nacos server 3.1.1 digest；3.3.0 只做过 artifact 兼容/依赖检查，未作为运行基线，且其 nacos-client shaded Guava 33.5.0 仍低于扫描报告修复版 33.7.2；九个应用在私网健康注册，Gateway 使用 lb 路由，Nacos 重启后自动恢复；默认栈仅发布 Gateway/监控端口，业务直连改为显式 debug 覆盖且已做配置及账户健康冒烟；业务中间件、配置导入/刷新矩阵和安全配置仍待验证）
- [ ] 任务 3：Gateway 外部路由白名单与用户/管理员鉴权（进行中；OIDC JWT 验证、默认拒绝、角色映射、身份头清理、Actuator 隔离及 15 条自动测试已实现；生产用精确 API 矩阵、共享限流、真实 Keycloak 联测仍待完成）
- [ ] 检查点 A：入口和供应链安全
- [ ] 任务 4：服务身份、网络分区和下游调用韧性（进行中；默认 Compose 不再发布业务服务端口，显式 debug 覆盖仅绑定回环地址；transaction Feign 已启用 OIDC client credentials，account/risk 分别校验签名、issuer、目标 audience 和 scope，负向集成测试通过；隔离 Compose 中 account/risk/transaction 健康并完成 Nacos 注册；真实 Keycloak 联测、调用方授权矩阵、token 失败/下游超时与服务调用故障测试仍待完成）
- [ ] 任务 5：统一错误、traceId、审计日志与业务指标（进行中；ADR-0010；account/risk/Gateway 已实现统一 400/401/403/500 和下游连接失败/超时错误、W3C `traceparent` 响应/传播、认证拒绝日志关联 trace ID、低基数平台指标白名单；Gateway 上游直接返回 5xx 的响应体统一化、共享限流及真实 429、资金/管理操作的持久审计、业务指标与保留策略仍待完成）
- [ ] 任务 6：数据源权限和 Flyway 迁移基线（进行中；ADR-0011；首批 account/transaction 已分离 `onecard_account`、`onecard_transaction` 及各自运行/迁移身份配置，只在这两个服务引入 JDBC/Flyway，生产强制凭据与 Flyway，空库不占用业务 V1；支付适配和清结算的 schema/身份仍待落实且不提前引入无用驱动；应用级首次/重复迁移、checksum 篡改、DDL/跨 schema 拒绝测试和一次性双 schema OceanBase smoke 已实现，19 模块 `clean verify` 通过；OceanBase 真实集成未执行成功：本机 Docker 总内存 8.25 GiB，不足脚本所需 10 GiB，7 GiB 尝试在 observer 启动后创建租户资源池失败；此前检查的共享开发服务器并非指定主机，其容量结论不适用；当前环境到指定 `admin1@192.168.0.41:22` 返回 `DestinationHostUnreachable`，尚未读取远端资源/容器，也未启动或修改远端容器；待主机恢复可达后先核验资源和现有负载，再运行 `scripts/oceanbase-account-smoke.ps1`，完成目标库验证后再关闭任务）
- [ ] 任务 7：本地开发入口和平台集成测试（进行中；README/监控说明已补齐 JDK 25、Maven wrapper、Docker Compose、k6、服务地址、首次 Nacos 配置和清理命令；`ci-local.ps1` 执行全量 `clean verify`、在唯一 Compose 项目中启动 Nacos/account、用 Nacos 3.x v3 API 检查 healthy/enabled/8082/`SPRING_CLOUD` 实例、重启 Nacos 验证重注册、运行 k6 并清理自身容器/卷；2026-11-10 两次本机完整运行通过，最后一次重启恢复通过，k6 20/20 checks 且 p95 26.15ms；PR CI 加入已构建 account 镜像的 Nacos 注册和重启恢复检查（待远端 Actions 首次结果），Gateway HTTP 上游代理/JWT/白名单/管理端口集成测试由 Maven `clean verify` PR 门禁执行；真实 Gateway+Nacos `lb://` 代理 CI、配置刷新/完整九服务恢复、OceanBase 迁移 PR smoke 仍未完成）
- [ ] 检查点 B：运行平台基础
- [ ] 任务 8：平台性能、压力和故障恢复验收
- [ ] 检查点 C：性能和恢复
- [ ] 阶段 1 退出评审
