# Phase 1 Gateway Platform Smoke

- Target: admin1@192.168.0.41
- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.
- Scenario: authenticated Gateway to isolated stub at 3,200 requests/minute for 30s.
- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.
- Requests: 1600; achieved rate: 3194.4/minute.
- Latency: p50=47.7 ms, p95=228.9 ms, p99=320.9 ms.
- Failed HTTP requests: 0%; functional checks: 3200/3200.
- Thresholds enforced: False
- User token and client secret are not included in this report.
- Raw k6, Prometheus and container samples are in this directory.
