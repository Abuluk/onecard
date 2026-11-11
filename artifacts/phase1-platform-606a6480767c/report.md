# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for 30m.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Stage 50 VUs (1m): 3196 requests, 3147/min, p50/p95/p99=2.9/5.9/9.2 ms, HTTP errors 0%, checks 9588/9588.
- Stage 100 VUs (1m): 3199 requests, 3103.5/min, p50/p95/p99=2.1/3/4.4 ms, HTTP errors 0%, checks 9597/9597.
- Stage 250 VUs (1m): 3204 requests, 2972/min, p50/p95/p99=1.9/2.6/3.9 ms, HTTP errors 0%, checks 9612/9612.
- Stage 500 VUs (30m): 95995 requests, 3183.3/min, p50/p95/p99=1.7/2.3/3.5 ms, HTTP errors 0%, checks 287985/287985.
- Total requests: 105594; HTTP status counts: 200=105594; non-200=0.
- Total functional checks: 316782/316782.
- Follow-up review: the 500-VU stage produced 95,995 requests, five below the 96,000 phase 0 target. HTTP and functional thresholds passed, but this run does not satisfy the required request count; the k6 script now enforces that minimum and the long run is being repeated.
- Peak container and load-generator resources (sampled approximately every 5s): onecard-phase1-keycloak-606a6480767c CPU 27.73% / 563 MiB; onecard-phase1-gateway-606a6480767c CPU 86.88% / 237.6 MiB; onecard-phase1-account-606a6480767c CPU 0.52% / 172.4 MiB; k6-load-generator CPU 21% / 228.7 MiB.
- Gateway JVM final scrape: process CPU 4.69%; heap used 54.2 MiB; GC 1081 collections / 2.017 s cumulative pause; executor active=0.0, queued=0.0.
- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.
- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.
- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.
- User token and client secret are not included in this report.
- Raw per-stage k6, Prometheus and container samples are in this directory.
