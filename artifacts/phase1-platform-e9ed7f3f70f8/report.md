# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub paced at 3,210 requests/minute to sustain the 3,200/minute minimum across 50/100/250/500 VU stages; the 500-VU stage runs for 30m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3208 requests, 3160.7/min, p50/p95/p99=2.9/5.8/9 ms, HTTP errors 0%, checks 9624/9624.
- Stage 100 VUs (1m): 3207 requests, 3113.1/min, p50/p95/p99=2/2.7/4.1 ms, HTTP errors 0%, checks 9621/9621.
- Stage 250 VUs (1m): 3204 requests, 2974.9/min, p50/p95/p99=1.9/2.5/3.9 ms, HTTP errors 0%, checks 9612/9612.
- Stage 500 VUs (30m): 96303 requests, 3193.5/min, p50/p95/p99=1.7/2.3/3.5 ms, HTTP errors 0%, checks 288909/288909.
- Total requests: 105922; HTTP status counts: 200=105922; non-200=0.
- Total functional checks: 317766/317766.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-e9ed7f3f70f8 CPU 34.56% / 560.6 MiB; onecard-phase1-gateway-e9ed7f3f70f8 CPU 99.56% / 242.6 MiB; onecard-phase1-account-e9ed7f3f70f8 CPU 0.35% / 170.4 MiB; k6-load-generator CPU 36.5% / 230.9 MiB.
- Gateway JVM final scrape: process CPU 4.69%; heap used 52.8 MiB; GC 1086 collections / 2.05 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: minimum request count matching 3,200/minute for each stage, HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
