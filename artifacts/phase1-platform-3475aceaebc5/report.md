# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for 10s.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3197 requests, 3149.2/min, p50/p95/p99=2.8/5.7/8.5 ms, HTTP errors 0%, checks 9591/9591.
- Stage 100 VUs (1m): 3200 requests, 3103/min, p50/p95/p99=2.1/2.8/4.1 ms, HTTP errors 0%, checks 9600/9600.
- Stage 250 VUs (1m): 3196 requests, 2964.4/min, p50/p95/p99=1.9/2.6/4.2 ms, HTTP errors 0%, checks 9588/9588.
- Stage 500 VUs (10s): 534 requests, 1655.4/min, p50/p95/p99=1.9/2.5/4.8 ms, HTTP errors 0%, checks 1602/1602.
- Total requests: 10127; HTTP status counts: 200=10127; non-200=0.
- Total functional checks: 30381/30381.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-3475aceaebc5 CPU 0.62% / 544.5 MiB; onecard-phase1-gateway-3475aceaebc5 CPU 94.46% / 236.5 MiB; onecard-phase1-account-3475aceaebc5 CPU 0.64% / 173.3 MiB; k6-load-generator CPU 24% / 150.6 MiB.
- Gateway JVM final scrape: process CPU 12.85%; heap used 53.1 MiB; GC 116 collections / 0.38 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
