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

The Compose file includes Nacos 3.1.1, all eight business services, the API
Gateway, and monitoring on a private Compose network. Nacos client and Console
ports are not published to the host. Nacos authentication is disabled only for
this isolated development network; do not attach untrusted containers or expose
the Nacos service. Applications use the `dev` profile, register in Nacos, and
Gateway routes use Nacos service discovery. The `test` profile remains isolated
from Nacos for unit and integration tests that do not need service discovery.
The generated `.env` is local-only and ignored by Git; `.env.example` contains
placeholders and must not be used as a real token.
MySQL and Redis remain private to N9E; application ports, N9E UI, and the
GreptimeDB HTTP API bind to localhost.

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

These modules currently expose their Actuator health and metrics endpoints.
Business routes and domain operations are added with their API contracts.
For standalone application tests without Nacos, set
`SPRING_PROFILES_ACTIVE=test`.

GreptimeDB write endpoint: `http://greptimedb:4000/v1/prometheus/write`.
N9E's Prometheus-like query endpoint: `http://greptimedb:4000/v1/prometheus`.

N9E data source, service dashboard, and disabled alert templates are stored as
JSON under `n9e/`; keep UI-only changes out of the source of truth.
GreptimeDB and Categraf runtime data must stay out of version control. This
local stack uses an empty MySQL root password on a private Compose network; do
not expose it outside a development machine.
