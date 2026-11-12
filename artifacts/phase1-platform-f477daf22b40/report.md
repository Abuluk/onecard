# Phase 1 Gateway Platform Smoke (failed)

- Scenario: authenticated Gateway to isolated stub at 3,200 requests/minute for 30s.
- Requests: 1601; achieved rate: 3196.3/minute.
- Latency: p50=45.7 ms, p95=82.7 ms, p99=108.5 ms.
- Failed HTTP requests: 0%; functional checks: 3202/3202.
- k6 response checks: 1601 stub responses passed; 1601 trace headers passed.
- This run failed; raw k6 and service logs are retained in this directory.
