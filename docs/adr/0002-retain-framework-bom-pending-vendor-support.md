# ADR-0002：采用 Spring Cloud Alibaba 官方列出的框架 BOM

**状态**：已决定；本地 clean verify、服务启动及 Nacos 基础联合验证通过
**日期**：2026-11-02；决策更新于 2026-11-03

## 背景

原根 `pom.xml` 使用 Spring Boot 4.1.2、Spring Cloud 2025.1.4 和 Spring Cloud Alibaba 2025.1.0.0。Spring Cloud Alibaba 官方版本表将 Alibaba 2025.1.0.0 对应到 Spring Cloud 2025.1.0 和 Spring Boot 4.0.0，并列出 Nacos 3.1.1。2026-11-03 官方 GitHub 最新发行版仍为 2025.1.0.0；原 POM 组合不在该版本表内。

原 BOM 下本地 Maven 全量构建和账户服务启动已通过，但这不能证明 Alibaba 组件组合获得官方支持。新 BOM 已完成全量构建、账户服务启动及 Nacos 九应用配置/注册和重启恢复联测；这仍不代表生产组合验证完成。

旧 BOM 下曾使用 Nacos 3.1.1 完成 Gateway 和 8 个服务的 `spring.config.import` 配置导入、gRPC 服务注册和实例查询，并通过 Gateway 的 `lb://` 路由访问账户服务。2026-11-03 改用官方对应 BOM 后，已重新构建九个 Compose 镜像并验证临时 DataId 导入、健康注册、Nacos 重启恢复及 Gateway 服务发现。Valkey/RocketMQ 分别完成基础读写/消息往返；另在开发服务器上完成 OceanBase 4.4.2.1 MySQL 模式下的基础 SQL 检查及 Flyway 13.12.0 空库迁移。

## 决策

- 将根 POM 对齐到官方版本表：Spring Boot 4.0.0、Spring Cloud 2025.1.0、Spring Cloud Alibaba 2025.1.0.0、Nacos 3.1.1。
- 用本机全量 `clean verify`、账户服务启动和 Nacos 九应用 Compose 联合验证新框架组合；旧 BOM 下的 PoC 保留为历史记录。
- 该官方对应关系不替代目标数据库应用集成、生产发行版、故障恢复、安全和容量验证。

## 备选方案

- 保留 Spring Boot 4.1.2 / Spring Cloud 2025.1.4 并等待单独支持函：会继续使用未列入官方对应表的组合，且引入额外外部依赖；不采用。
- 继续沿用当前版本并直接宣称兼容：缺少依据；不采用。
- 对齐官方版本表后验证：采用，可使用公开列出的对应组合并消除这一项版本悬案。

## 后果

- 框架基线对齐到官方明确列出的 2025.1 组合；本地全量构建、服务启动和 Nacos 基础联测通过后，作为开发基线使用。
- Nacos 3.1.1 与该 Alibaba 版本表中的组件版本一致；其他中间件仍按各自矩阵和 PoC 状态管理。
- 生产发行版、数据库 Spring 集成、完整故障、安全和容量验证仍是独立工作项。

## 复审条件

- 官方发布新的 Spring Cloud Alibaba 兼容版本表时复审。
- 新组合的全量构建、服务启动或 Nacos 配置/发现验证失败时复审。

## 来源

- Spring Cloud Alibaba 官方版本说明：<https://sca.aliyun.com/docs/2025.x/overview/version-explain/>
- Spring Cloud Alibaba 官方 GitHub 最新发行版：<https://github.com/alibaba/spring-cloud-alibaba/releases/latest>
- Spring Cloud 2025.1 参考文档：<https://docs.spring.io/spring-cloud-release/reference/2025.1/index.html>
- Spring Boot 4.0 系统要求：<https://docs.spring.io/spring-boot/4.0/system-requirements.html>

