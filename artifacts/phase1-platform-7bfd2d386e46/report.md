# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at 3,200 requests/minute for 30m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Requests: 96001; achieved rate: 3200/minute.
- Latency: p50=44 ms, p95=68.1 ms, p99=82.6 ms.
- Failed HTTP requests: 0%; functional checks: 192002/192002.
- HTTP status counts: 200=96001; non-200=0.
- Load generator VUs: observed max 28, configured max 500; workload data: stateless stub response, no database records.
- Peak container resources (sampled every 5s): onecard-phase1-keycloak-7bfd2d386e46 CPU 23.35% / 557.4 MiB; onecard-phase1-gateway-7bfd2d386e46 CPU 85.53% / 233.6 MiB; onecard-phase1-account-7bfd2d386e46 CPU 0.51% / 171.2 MiB.
- Gateway JVM final scrape: process CPU 5.09%; heap used 31.3 MiB; GC 996 collections / 1.783 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Thresholds enforced: False
- User token and client secret are not included in this report.
- Raw k6, Prometheus and container samples are in this directory.
