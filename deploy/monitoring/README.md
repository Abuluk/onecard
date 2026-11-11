# Monitoring

Start the local application and monitoring stack with:

```powershell
cd ../..
./mvnw.cmd clean package
if (Test-Path .env) { throw 'Keep the existing .env and update its Nacos token manually.' }
$token = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
@(
  "NACOS_AUTH_TOKEN=$token"
  "NACOS_AUTH_IDENTITY_KEY=serverIdentity"
  "NACOS_AUTH_IDENTITY_VALUE=onecard-local"
) | Set-Content .env -Encoding ascii
docker compose -f deploy/monitoring/docker-compose.yml up --build
```

The default stack publishes only the API Gateway and monitoring endpoints to
the host, all bound to loopback. To connect to business services directly for
local debugging, explicitly add the debug override:

```powershell
docker compose -f deploy/monitoring/docker-compose.yml -f deploy/monitoring/docker-compose.debug.yml up --build
```

The Compose file includes Nacos 3.1.1, all eight business services, the API
Gateway, private Valkey rate-limit storage, and monitoring on a private Compose network. Nacos client and Console
ports are not published to the host. Nacos authentication is disabled only for
this isolated development network; do not attach untrusted containers or expose
the Nacos service. Applications use the `dev` profile, register in Nacos, and
Gateway routes use Nacos service discovery. The `test` profile remains isolated
from Nacos for unit and integration tests that do not need service discovery.
No remote DataId is required for the service skeletons. Optional development
overrides use `<spring.application.name>.yaml` in `DEFAULT_GROUP`; do not store
credentials, OIDC client secrets, or other secrets in Nacos development config.
The generated `.env` is local-only and ignored by Git; `.env.example` contains
placeholders and must not be used as a real token.
MySQL and Redis remain private to N9E. Valkey is separate, volatile counter
storage with no persistent volume and does not represent a production HA setup.
The Gateway, N9E UI, and GreptimeDB HTTP
API bind to localhost; business service ports are not published by default.

OceanBase and RocketMQ are available as optional, digest-pinned business
profiles. They are excluded from the default smoke because OceanBase requires
up to 10 GiB of container memory and the services are only needed for database
or messaging development. The OceanBase service name matches the JDBC URLs in
`.env.example`; the RocketMQ broker and nameserver stay on the private Compose
network and publish no host ports. Their named data volumes are preserved by
the stop command.

```powershell
docker compose --profile business-data --profile business-messaging `
  -f deploy/monitoring/docker-compose.yml up -d onecard-oceanbase onecard-rocketmq-broker

docker compose --profile business-data --profile business-messaging `
  -f deploy/monitoring/docker-compose.yml stop onecard-oceanbase onecard-rocketmq-broker
```

Local application ports:

| Module | Port |
| --- | ---: |
| API Gateway | 8081 |
| Account | 8082 |
| Transaction | 8083 |
| Payment adapter | 8084 |
| Clearing | 8085 |
| Notification | 8086 |
| Risk | 8087 |
| Access | 8088 |
| Device | 8089 |

These container ports are reachable from the Compose network. Direct host
access requires the explicit debug override above. The modules currently expose
their Actuator health and metrics endpoints; business routes and domain
operations are added with their API contracts.
For standalone application tests without Nacos, set
`SPRING_PROFILES_ACTIVE=test`.

Run `./ci-local.ps1` for Maven verification, isolated Nacos/account startup,
registration and Nacos restart recovery checks, and the k6 health smoke. Stop the regular local
stack while preserving its data with:

```powershell
docker compose -f deploy/monitoring/docker-compose.yml down
```

Use `down --volumes` only when intentionally deleting local Nacos, MySQL, and
GreptimeDB data. The local CI script uses its own Compose project and removes
only that project's temporary containers and volume.

GreptimeDB write endpoint: `http://greptimedb:4000/v1/prometheus/write`.
N9E's Prometheus-like query endpoint: `http://greptimedb:4000/v1/prometheus`.

N9E data source, service dashboard, and disabled alert templates are stored as
JSON under `n9e/`; keep UI-only changes out of the source of truth.
GreptimeDB and Categraf runtime data must stay out of version control. This
local stack uses an empty MySQL root password on a private Compose network; do
not expose it outside a development machine.
