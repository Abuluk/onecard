# Monitoring

Start the local application and monitoring stack with:

```powershell
cd ../..
./mvnw.cmd clean package
docker compose -f deploy/monitoring/docker-compose.yml up --build
```

The Compose file includes the Demo service and Gateway so Categraf can scrape
them on the shared Compose network. MySQL and Redis are private to that network;
only the application ports, N9E UI, and GreptimeDB HTTP API bind to localhost.

GreptimeDB write endpoint: `http://greptimedb:4000/v1/prometheus/write`.
N9E's Prometheus-like query endpoint: `http://greptimedb:4000/v1/prometheus`.

N9E importable data source, service dashboard, and disabled alert templates are
stored as JSON under `n9e/`; keep UI-only changes out of the source of truth.
GreptimeDB and Categraf runtime data must stay out of version control. This
local stack uses an empty MySQL root password on a private Compose network; do
not expose it outside a development machine.
