# ADR-0011：服务数据库所有权与 Flyway 基线

**状态**：采用为开发基线；应用级 OceanBase 集成验证进行中  
**日期**：2026-11-10

## 背景

OceanBase CE 4.4.2.1 MySQL 模式已有基础 SQL 与 Flyway CLI 空库 PoC，但账户服务尚未配置 JDBC、应用账号、迁移账号或可重复的 Spring 集成验证。账户服务尚无业务表；不能通过空的 V1 迁移占用首次业务版本号。

## 决策

1. 账户服务独占账户、卡、余额、账本和账户 outbox 数据；交易服务独占交易状态、幂等请求、终端序号及补传进度。两边只通过服务 API/事件交互，不共享表或数据库事务。该边界落实 [ADR-0008](0008-idempotent-account-operations-and-reliable-progression.md)。
2. 账户和交易 schema/database 分别为 `onecard_account` 与 `onecard_transaction`。每个 schema 各有应用身份和迁移身份；应用身份仅获本 schema 的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`，迁移身份仅获本 schema 的迁移所需 DDL/DML。Spring JDBC 使用应用身份，Flyway 使用独立迁移身份。Gateway 不配置业务数据库依赖或凭据。
3. 本轮先为账户与交易两个资金主链路服务加入 Spring JDBC、MySQL Connector/J 和 Flyway。支付适配服务拥有外部请求/回调状态，清结算服务拥有批次/差错状态；在这两个服务实现持久化 API 前，不添加无用数据库依赖，但必须先评审并配置各自独立 schema 和身份。其他服务同样不得共享上述 schema。
4. 生产连接配置通过 `ACCOUNT_DB_URL`、`ACCOUNT_DB_USERNAME`、`ACCOUNT_DB_PASSWORD`、`ACCOUNT_DB_MIGRATION_USERNAME`、`ACCOUNT_DB_MIGRATION_PASSWORD` 注入。迁移 URL 可用 `ACCOUNT_DB_MIGRATION_URL` 单独指定。不得把凭据提交到仓库、日志或异常响应。
5. 生产迁移目录固定为 `services/onecard-account-service/src/main/resources/db/migration`，文件名为 `V<version>__<description>.sql`。应用启动时 Flyway 验证 checksum 并执行待运行迁移；baseline-on-migrate、out-of-order 和 clean 均关闭。空 schema 必须由受控数据库初始化步骤预先创建，Flyway 不创建 schema。当前目录不含业务迁移；真实首张业务表按评审后的模型作为 V1 提交。
6. 迁移失败必须中断服务启动并保留故障证据。不得自动执行 `clean` 或 `repair`；先确认 DDL 的部分提交状态、备份和实际 schema，再由具备审批的操作者按数据库恢复步骤处理。不能假定 OceanBase MySQL 模式下 DDL 可自动回滚。
7. 测试迁移仅存在于 `src/test/resources/db/migration-smoke`，使用隔离 schema，不进入生产 JAR，也不占用生产版本号。集成验证必须使用目标 OceanBase，而不是 N9E 的 MySQL 替代。

## 后果

- 账户和交易服务可分别通过环境变量连接其专属 OceanBase schema，并将 Flyway DDL 与运行期 DML 权限分开。
- `.env.example` 中的数据库开关默认关闭，用于当前尚无业务表的本地骨架健康冒烟。相应服务一旦启用持久化 API，开发环境必须启用对应数据库；生产配置始终启用 Flyway。
- 支付适配和清结算的 schema/身份配置仍未落地；在任一服务开始持久化实现前，须先确定隔离身份和权限，再单独引入数据库依赖。
- OceanBase Spring JDBC/连接池、重启、checksum、DDL 权限和双向跨 schema 拒绝须由任务 6 的集成 smoke 记录验证；HA/RPO、并发和备份恢复仍是后续独立门禁。

## 复审条件

账户服务由 Spring JDBC 切换持久层、目标数据库/驱动升级、Flyway 迁移权限策略改变，或出现跨服务持久化所有权争议时复审。
