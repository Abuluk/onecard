# AGENTS.md

## 编码前思考
- 明确假设，不确定时询问而非猜测。
- 存在歧义时，列出多种解释，不默默选定单一方案。
- 如果任务有明显更简单的做法，直接指出优化思路。
- 发现代码矛盾、逻辑不一致时及时暂停，请求信息澄清。

## 简洁优先
- 用最少的代码解决问题，拒绝冗余实现。
- 不为一次性需求创建抽象层、复杂架构。
- 不盲目增加扩展性、可配置性，应对"未来可能用到"的场景。
- 若代码可大幅精简，主动重写优化。
- 校验标准：以资深工程师视角判断，代码若过于复杂，立即简化。

## 精准修改
- 仅修改与当前任务直接相关的代码内容。
- 不顺手优化相邻代码、注释、排版格式。
- 不重构原本可以正常运行的代码模块。
- 严格匹配项目现有代码风格，保留原有编码习惯。
- 因本次修改产生的无效导入、废弃变量，可直接删除。
- 发现项目中原有的死代码、冗余内容，仅做文字提醒，不擅自删除。

## 目标驱动执行
- 执行任务前，定义清晰、可落地的成功标准。
- 将"修复Bug"转化为：编写用例复现问题，再调试至用例正常通过。
- 将"新增校验功能"转化为：针对异常输入编写测试用例，保证全部通过。
- 将"代码重构"转化为：完成重构后，确保原有所有测试用例正常运行。
- 多步骤复杂任务，先输出简短执行计划，同时标注每一步的验证方式。

---

# 项目说明

- 本项目为基于 Spring Cloud 的微服务后端工程
- 构建工具：Maven
- 版本以根 `pom.xml` 的 `dependencyManagement` 为准，下述为截至 2026 年 Q4 的常见组合：
  - **JDK 25**（Boot 4.x 最低要求 JDK 17，官方推荐生产使用 JDK 25 LTS）
  - **Spring Boot 4.1.x**
  - **Spring Cloud 2025.1.x（Oakwood）** —— 对应 Boot 4.0.x/4.1.x 的稳定列车
  - **Spring Cloud Alibaba 2025.1.0.0** —— 正式 BOM 官方声明适配 Boot 4.0.x + Cloud 2025.1.x；对 Boot 4.1 的兼容分支已就绪，但正式 BOM 尚未发布，选择时需注意版本对齐
- 常用组件：
  - 注册中心 / 配置中心：Nacos
  - 网关：Spring Cloud Gateway
  - 服务调用：OpenFeign
  - 熔断限流：Sentinel
  - 分布式事务：Seata
  - 消息：RocketMQ
  - 持久层：MyBatis-Plus
  - 缓存：Valkey
  - 数据库:OceanBase 
  - 数据库迁移：Flyway
- **可观测性**：
  - 采集器：Categraf（All-In-One，负责 JVM、主机、中间件指标）
  - 监控告警平台：夜莺 Nightingale（n9e，负责告警引擎、仪表盘、数据源管理）
  - 时序存储：GreptimeDB（Prometheus Remote Write / PromQL 兼容）

## Project Structure
project-root/
├── deploy/
│   └── monitoring/            # 自建监控栈，与业务代码隔离
│       ├── docker-compose.yml # n9e / categraf / greptimedb
│       ├── categraf/          # 采集配置，按服务分文件
│       │   └── input.prometheus.toml
│       ├── n9e/               # 夜莺数据源、告警规则、大盘 JSON
│       └── greptimedb/        # 时序库配置与数据目录挂载（不提交数据）
├── tests/
│   └── performance/           # k6 性能测试脚本，一接口一文件或一场景一文件
│       ├── lib/               # 共享：数据工厂、登录封装、公共断言
│       ├── smoke.js           # 冒烟（1 VU，验证接口可用 + 基础阈值）
│       └── <resource>.js      # 负载/峰值/浸泡场景
├── pom.xml # 根 POM，统一版本与依赖管理
├── <common-module>/ # 公共模块：Result、异常、常量、工具
├── <gateway-module>/ # 网关服务
├── <xxx-service>/ # 业务微服务（每个服务独立可启动）
│ ├── src/main/java/.../
│ │ ├── controller/ # 接口层，只做参数校验与结果组装
│ │ ├── service/ # 业务逻辑；默认直接写实现类，不强制接口
| |   |—— impl/# 仅当存在多实现或需跨模块暴露接口时才建
│ │ ├── mapper/ # 数据访问
│ │ ├── entity/ # 数据库实体
│ │ ├── dto/ # 入参对象
│ │ ├── vo/ # 出参对象
│ │ ├── convert/ # 对象转换
│ │ ├── config/ # 配置类
│ │ └── feign/ # 远程调用客户端
│ └── src/main/resources/
│ ├── application.yml
│ ├── bootstrap.yml # 使用 Nacos 配置中心
│ └── mapper/ # MyBatis XML
└── <xxx-api>/ # 服务对外契约（Feign 接口 + DTO），供其他服务依赖

- Never edit `target/` — 构建产物
- Never edit 自动生成的代码（如 MapStruct 生成实现、代码生成器输出目录）
- 跨服务共享的对象放 `api` 模块，禁止直接依赖其他服务的内部实现模块

## Coding Style

- 类名 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE
- 使用 `record` 或显式 DTO 类承载请求/响应，禁止直接把 `entity` 暴露给接口
- Never 使用 `var` 声明字段；局部变量可用
- Never 捕获 `Exception` 后吞掉不处理，禁止空 catch 块
- Never 在 Controller 里写业务逻辑
- 依赖注入优先构造器注入，禁止字段注入（`@Autowired` 标在字段上）
- 使用 Lombok 时统一风格：`@RequiredArgsConstructor` + `@Slf4j`，不混用 `@Data` 与手写 getter
- 时间统一使用 `java.time`（`LocalDateTime` / `Instant`），禁止 `java.util.Date`
- 金额统一 `BigDecimal`，禁止 `double` / `float`
- k6 脚本使用 ESM：`import http from 'k6/http'`，禁止 CommonJS
- 环境地址一律从 `__ENV.BASE_URL` 读取，脚本内禁止硬编码 host[reference:1]
- 每个脚本 `options.thresholds` 必须至少包含：
  - `http_req_failed: ['rate<0.01']`（错误率 < 1%）
  - 关键接口的 `http_req_duration: ['p(95)<500']`（按接口 SLO 调整）
  - 业务断言必须挂到 `checks` 上，并对 `checks` 设 `rate>0.99` 阈值[reference:2]
- 禁止只写 `check()` 而不配阈值——**checks 不影响退出码，阈值才影响**
- 数据准备与清理必须幂等，重复执行不产生脏数据

## DTO 规范

- 同一用途只允许一个 DTO 类，禁止为同一结构复制出 `XxxDTO` / `XxxDto` / `XxxRequest` / `XxxParam` 等别名类
- 命名后缀按用途统一，全项目不混用：
  - 入参：`XxxDTO`（写操作）/ `XxxQuery`（查询条件）
  - 出参：`XxxVO`
- 禁止用 `@JsonAlias` / `@JsonProperty` 给同一字段挂多个别名来兼容历史调用方；确需兼容走网关适配层或接口版本，不在 DTO 上堆别名
- 禁止直接复用 `entity` 作为接口出入参（即"换个名字当别名"）

## 接口与分层规范

- 统一返回体：`Result<T>`（code / message / data），由 common 模块提供
- 统一异常：业务异常抛 `BizException`，由 `@RestControllerAdvice` 全局处理；禁止在 Controller 里 try-catch 后返回错误码
- 入参校验使用 `jakarta.validation` 注解 + `@Validated`，禁止手写大量 if 判空
- 分页参数与响应使用统一封装（如 `PageQuery` / `PageResult`），不自造格式
- 接口路径统一前缀：`/api/<服务名>/<资源>`，动词用 HTTP 方法表达
- Feign 客户端必须显式配置 `fallback` 或 `fallbackFactory`，并设置超时
- 服务间调用禁止硬编码地址，必须走注册中心或配置中心

## 接口可用性

- 每个服务必须引入 `spring-boot-starter-actuator`，`/actuator/health` 必须纳入数据库、Redis、注册中心等关键依赖的健康指示器
- 对外接口必须显式声明超时：Feign 客户端配置 `connectTimeout` / `readTimeout`，网关路由配置 `response-timeout`，禁止依赖默认值
- Feign 客户端必须配置 `fallback` 或 `fallbackFactory`，降级方法返回统一 `Result` 的失败结构，禁止抛异常穿透到调用方
- 写接口必须支持幂等：由调用方传幂等键或由业务天然幂等，幂等逻辑落在 Service 层并在接口文档注明
- 接口变更遵循兼容优先：新增字段允许，删除/改类型/改语义必须走新版本路径（`/v2/...`）或新接口，禁止原地破坏性修改
- 对外暴露的 DTO 变更需同步更新 `<xxx-api>` 模块，并跑通调用方编译
- 上线前必须提供接口冒烟用例（见「测试要求」），冒烟不过不允许发布

## 可观测性规范

- 在已引入的 actuator 基础上追加 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus`
- `management.endpoints.web.exposure.include` 只允许 `health,info,prometheus`，**禁止设为 `*`**——`*` 会暴露 `env`、`heapdump`、`shutdown` 等敏感端点
- 应用侧不挂 Java agent，JVM / HTTP / 连接池 / 线程池指标统一由 Actuator 的 Prometheus 端点提供，由 Categraf `input.prometheus` 抓取
- Categraf 抓取配置的 `labels` 中必须填写 `application` 标签，值为服务名，用于夜莺大盘按应用名区分
- 夜莺的时序库必须配置为 GreptimeDB（或项目实际使用的兼容 Prometheus 的时序库），写入走 `[[Pushgw.Writers]]`，查询侧数据源选 “Prometheus like”
- 告警规则从夜莺「集成中心」的 SpringBoot 模板导入后默认是停用的，必须先确认指标入库再逐条启用
- 应用新增自定义 Micrometer 指标时，指标名和标签需同步登记到夜莺告警规则/大盘，避免指标口径分裂
- 监控栈统一由 `deploy/monitoring/docker-compose.yml` 编排，n9e / categraf / greptimedb 三个服务在同一 compose 内，禁止把其中一个拆到别处单独部署
- GreptimeDB 的写入端点固定为 `http://greptimedb:4000/v1/prometheus/write`，夜莺 `[[Pushgw.Writers]]` 和查询数据源 `http://greptimedb:4000/v1/prometheus` 必须与此一致；改端口需同时改 compose、夜莺配置和数据源，三处缺一不可
- 新增一个受监控的服务时，必须同时提交：Categraf 对应的 `input.prometheus.toml`、夜莺数据源中该服务的 `application` 标签值、以及该服务的告警规则；**只加服务不加采集配置视为未完成**
- 修改 Categraf 采集配置或夜莺告警规则后，必须在本地 compose 起栈验证指标入库、告警可触发，再提交
- 夜莺的数据源、告警规则、大盘以 JSON 形式存放在 `deploy/monitoring/n9e/`，**禁止只在夜莺 UI 上改而不落盘**——UI 改动视为临时调试，最终以仓库内 JSON 为准

## 数据库规范

- 表名、字段名 snake_case，禁止使用保留字
- 每张表必须有主键、`create_time`、`update_time`
- 禁止 `SELECT *`，查询字段显式列出
- 禁止在循环中执行 SQL，批量操作用 `foreach` / 批量接口
- 分页查询必须有明确排序字段，避免结果不稳定
- 变更表结构需提供迁移脚本（Flyway / Liquibase，按项目实际），不直接手改线上库

## 测试要求

分层测试，每层只测自己该测的，不重复覆盖：

- **Service 层**：JUnit 5 + Mockito，隔离 Mapper 和 Feign；每个 public 方法至少覆盖正常路径 + 边界值 + 一个异常路径
- **Controller 层**：`@WebMvcTest` + `MockMvc`，校验参数绑定、`@Validated` 生效、全局异常处理返回结构；不启动完整容器
- **Feign 客户端**：用 WireMock / MockWebServer 模拟下游，断言请求路径、方法、请求体、超时和降级逻辑；禁止在单测里发起真实远程调用
- **集成测试**：`@SpringBootTest` + Testcontainers（MySQL / Redis / Nacos 按需），只覆盖跨层关键链路，不追求全覆盖
- **接口可用性冒烟**：每个服务提供 `XxxApiSmokeTest`，启动容器后依次请求核心接口 + `/actuator/health`，断言 HTTP 200 且 `Result.code` 为成功；作为 CI 必过项
- 修改公共模块（common / api）后，必须运行全量测试确认无回归
- 测试类命名：`<被测类名>Test`（单测）/ `<被测类名>IT`（集成），与被测类同包
- 禁止为通过测试而放宽断言（如只断言 `not null`、吞异常），断言必须校验业务语义

## 日志

- 使用 SLF4J + 占位符：`log.info("orderId={}, status={}", id, status)`，禁止字符串拼接
- 禁止打印敏感信息（手机号、身份证、密码、token）
- 异常日志必须带堆栈：`log.error("xxx failed, id={}", id, e)`
- 关键链路日志需带 `traceId`（由网关/MDC 透传，不自行生成）

## 配置与安全

- 配置按环境分离：`application-{dev|test|prod}.yml`，敏感配置（密码、密钥）不写入仓库，环境变量只放“引导级”和“基线级”配置，业务配置和动态配置，全部交给 Nacos。
- 禁止在代码中硬编码密钥、AK/SK、内网地址
- 对外接口需鉴权，网关层统一校验，服务内不重复实现
- 定时任务、消息消费需考虑幂等，禁止假设消息只投递一次
- 禁止提交没有对应测试的 public 业务方法（纯 getter/setter、DTO、配置类除外）
- 禁止在 CI 中跳过测试（`-DskipTests` 仅限本地临时构建）
- 禁止删除或注释已有测试来让流水线变绿
- 禁止把监控栈配置散落到各服务模块，统一收口到 `deploy/monitoring/`
- 禁止在夜莺 UI 上直接改数据源/告警规则/大盘而不更新 `deploy/monitoring/n9e/` 下的 JSON
- 禁止提交 GreptimeDB 的数据目录、Categraf 的本地缓存目录

## Build Commands

```bash
./mvnw clean package                    # 全量构建
./mvnw clean package -DskipTests        # 跳过测试构建
./mvnw -pl <module> -am clean package   # 构建单个模块及其依赖
./mvnw spring-boot:run -pl <module>     # 本地启动单个服务
./mvnw test                             # 运行全部单元测试
./mvnw test -pl <module>                # 运行指定模块测试
./mvnw spotless:apply                   # 格式化（若项目接入 Spotless）