# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for 1m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3199 requests, 3149.7/min, p50/p95/p99=2.9/5.8/8.9 ms, HTTP errors 0%, checks 9597/9597.
- Stage 100 VUs (1m): 3197 requests, 3102.1/min, p50/p95/p99=2/2.7/4 ms, HTTP errors 0%, checks 9591/9591.
- Stage 250 VUs (1m): 3198 requests, 2968.1/min, p50/p95/p99=1.9/2.6/4.1 ms, HTTP errors 0%, checks 9594/9594.
- Stage 500 VUs (1m): 3207 requests, 2773.7/min, p50/p95/p99=1.9/2.4/3.6 ms, HTTP errors 0%, checks 9621/9621.
- Total requests: 12801; HTTP status counts: 200=12801; non-200=0.
- Total functional checks: 38403/38403.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-9083cf83e160 CPU 2.21% / 546.5 MiB; onecard-phase1-gateway-9083cf83e160 CPU 86.05% / 238.3 MiB; onecard-phase1-account-9083cf83e160 CPU 0.33% / 171.1 MiB; k6-load-generator CPU 24.5% / 187.5 MiB.
- Gateway JVM final scrape: process CPU 11.28%; heap used 35 MiB; GC 144 collections / 0.47 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
