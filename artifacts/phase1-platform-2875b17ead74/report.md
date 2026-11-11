# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for 10s.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3199 requests, 3150.5/min, p50/p95/p99=2.9/6.1/8.8 ms, HTTP errors 0%, checks 9597/9597.
- Stage 100 VUs (1m): 3199 requests, 3103/min, p50/p95/p99=2.1/2.9/3.9 ms, HTTP errors 0%, checks 9597/9597.
- Stage 250 VUs (1m): 3199 requests, 2967.3/min, p50/p95/p99=1.9/2.6/4 ms, HTTP errors 0%, checks 9597/9597.
- Stage 500 VUs (10s): 527 requests, 1632.3/min, p50/p95/p99=1.8/2.4/3.2 ms, HTTP errors 0%, checks 1581/1581.
- Total requests: 10124; HTTP status counts: 200=10124; non-200=0.
- Total functional checks: 30372/30372.
- Peak container resources (sampled every 5s): onecard-phase1-keycloak-2875b17ead74 CPU 0.52% / 546.6 MiB; onecard-phase1-gateway-2875b17ead74 CPU 90.91% / 234.4 MiB; onecard-phase1-account-2875b17ead74 CPU 0.27% / 167.9 MiB; k6-load-generator CPU 19.7% / 151.8 MiB.
- Gateway JVM final scrape: process CPU 12.54%; heap used 53.1 MiB; GC 117 collections / 0.414 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
