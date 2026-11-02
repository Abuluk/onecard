# Monitoring

Start the local application and monitoring stack with:

```powershell
cd ../..
./mvnw.cmd clean package
docker compose -f deploy/monitoring/docker-compose.yml up --build
```

The Compose file includes all eight business services and the API Gateway so
Categraf can scrape them on the shared Compose network. MySQL and Redis are
private to that network; application ports, N9E UI, and the GreptimeDB HTTP API
bind to localhost.

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

GreptimeDB write endpoint: `http://greptimedb:4000/v1/prometheus/write`.
N9E's Prometheus-like query endpoint: `http://greptimedb:4000/v1/prometheus`.

N9E data source, service dashboard, and disabled alert templates are stored as
JSON under `n9e/`; keep UI-only changes out of the source of truth.
GreptimeDB and Categraf runtime data must stay out of version control. This
local stack uses an empty MySQL root password on a private Compose network; do
not expose it outside a development machine.
