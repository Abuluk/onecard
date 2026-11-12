# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub paced at 3,210 requests/minute to sustain the 3,200/minute minimum across 50/100/250/500 VU stages; the 500-VU stage runs for 1m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3210 requests, 3162.3/min, p50/p95/p99=2.8/5.7/8.5 ms, HTTP errors 0%, checks 9630/9630.
- Stage 100 VUs (1m): 3206 requests, 3109.6/min, p50/p95/p99=2.1/2.8/4.1 ms, HTTP errors 0%, checks 9618/9618.
- Stage 250 VUs (1m): 3210 requests, 2978.1/min, p50/p95/p99=1.9/2.6/4.2 ms, HTTP errors 0%, checks 9630/9630.
- Stage 500 VUs (1m): 3209 requests, 2777/min, p50/p95/p99=1.8/2.4/3.8 ms, HTTP errors 0%, checks 9627/9627.
- Total requests: 12835; HTTP status counts: 200=12835; non-200=0.
- Total functional checks: 38505/38505.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-1829d6d7f86b CPU 5.81% / 561.4 MiB; onecard-phase1-gateway-1829d6d7f86b CPU 80.17% / 236.5 MiB; onecard-phase1-account-1829d6d7f86b CPU 0.17% / 171.3 MiB; k6-load-generator CPU 22.3% / 188.2 MiB.
- Gateway JVM final scrape: process CPU 11.48%; heap used 47.8 MiB; GC 145 collections / 0.441 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: minimum request count matching 3,200/minute for each stage, HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
