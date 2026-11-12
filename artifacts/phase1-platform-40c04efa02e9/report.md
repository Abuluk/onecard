# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at 3,200 requests/minute for 30s.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Requests: 1601; achieved rate: 3186.3/minute.
- Latency: p50=44 ms, p95=78.3 ms, p99=98.4 ms.
- Failed HTTP requests: 0%; functional checks: 3202/3202.
- HTTP status counts: gateway_response_status=1601.
- Load generator VUs: observed max 5, configured max 500; workload data: stateless stub response, no database records.
- Peak container resources (sampled every 5s): onecard-phase1-keycloak-40c04efa02e9 CPU 0.23% / 549.2 MiB; onecard-phase1-gateway-40c04efa02e9 CPU 97.81% / 225.7 MiB; onecard-phase1-account-40c04efa02e9 CPU 0.11% / 169.1 MiB.
- Gateway JVM final scrape: process CPU 32.88%; heap used 45 MiB; GC 27 collections / 0.191 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Thresholds enforced: False
- User token and client secret are not included in this report.
- Raw k6, Prometheus and container samples are in this directory.
