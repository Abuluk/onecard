# OneCard Platform

Spring Cloud monorepo for the eight services listed in
[`docs/微服务划分.md`](docs/微服务划分.md). Each service is an independent Maven
module under `services/`; shared Feign contracts live under `apis/`.

Build and test all modules:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress verify
```

Start the local services and monitoring stack after building:

```powershell
docker compose -f deploy/monitoring/docker-compose.yml up --build
```

Before first startup, create an ignored local `.env` with a Nacos token as
described in [`deploy/monitoring/README.md`](deploy/monitoring/README.md).

The local Compose stack uses the `dev` profile and Nacos service discovery. It
is for development only; `test` disables Nacos for isolated test runs.
See [`docs/项目说明.md`](docs/项目说明.md) for module conventions and
[`deploy/monitoring/README.md`](deploy/monitoring/README.md) for service URLs.
The ordered implementation stages and their test/performance gates are in
[`docs/开发阶段规划.md`](docs/开发阶段规划.md).

The current Compose stack starts runnable service skeletons and monitoring.
Its MySQL and Redis instances are private to N9E. Nacos is private to the
Compose network; a business database, cache, and message broker are not part of
the local stack yet.

When adding a service, add its Maven module, application profiles, Dockerfile,
API contract module, gateway route, Compose health check, Categraf scrape entry,
and N9E alert/dashboard changes together.
