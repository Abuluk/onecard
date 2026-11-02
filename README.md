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

The local Compose stack uses the `test` profile and container DNS routes. It is
for development only; production configuration is supplied through Nacos.
See [`docs/项目说明.md`](docs/项目说明.md) for module conventions and
[`deploy/monitoring/README.md`](deploy/monitoring/README.md) for service URLs.

When adding a service, add its Maven module, application profiles, Dockerfile,
API contract module, gateway route, Compose health check, Categraf scrape entry,
and N9E alert/dashboard changes together.
