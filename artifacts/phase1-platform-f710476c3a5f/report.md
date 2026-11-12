# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for 30m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3197 requests, 3149.2/min, p50/p95/p99=2.9/6.5/9.9 ms, HTTP errors 0%, checks 9591/9591.
- Stage 100 VUs (1m): 3199 requests, 3103.3/min, p50/p95/p99=2.1/2.8/4.1 ms, HTTP errors 0%, checks 9597/9597.
- Stage 250 VUs (1m): 3207 requests, 2974.7/min, p50/p95/p99=1.9/2.5/3.8 ms, HTTP errors 0%, checks 9621/9621.
- Stage 500 VUs (30m): 95987 requests, 3183/min, p50/p95/p99=1.7/2.3/3.4 ms, HTTP errors 0%, checks 287961/287961.
- Total requests: 105590; HTTP status counts: 200=105590; non-200=0.
- Total functional checks: 316770/316770.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-f710476c3a5f CPU 84.19% / 552.9 MiB; onecard-phase1-gateway-f710476c3a5f CPU 94.83% / 244.4 MiB; onecard-phase1-account-f710476c3a5f CPU 1.08% / 167.1 MiB; k6-load-generator CPU 32.5% / 229.6 MiB.
- Gateway JVM final scrape: process CPU 4.69%; heap used 43.1 MiB; GC 1089 collections / 2.056 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
