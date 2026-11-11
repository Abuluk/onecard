# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at 3,200 requests/minute for 30s.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Requests: 1600; achieved rate: 3192.5/minute.
- Latency: p50=48.3 ms, p95=81.2 ms, p99=100.5 ms.
- Failed HTTP requests: 0%; functional checks: 3200/3200.
- HTTP status counts: 200=1600; non-200=0.
- Load generator VUs: observed max 4, configured max 500; workload data: stateless stub response, no database records.
- Peak container resources (sampled every 5s): onecard-phase1-keycloak-584ce5627698 CPU 2.25% / 544.3 MiB; onecard-phase1-gateway-584ce5627698 CPU 79.64% / 235.5 MiB; onecard-phase1-account-584ce5627698 CPU 0.13% / 168.8 MiB.
- Gateway JVM final scrape: process CPU 32.9%; heap used 37 MiB; GC 27 collections / 0.218 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Functional checks: rate==1 enforced; production SLO thresholds enforced: False (no production SLO approved for this development baseline).
- User token and client secret are not included in this report.
- Raw k6, Prometheus and container samples are in this directory.
