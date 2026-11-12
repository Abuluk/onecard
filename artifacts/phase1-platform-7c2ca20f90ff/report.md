# Phase 1 Gateway Platform Smoke (failed)

- Failed stage: 50 VUs for 1m; target pacing is 3,200 requests/minute.
- Requests: 3197; achieved rate: 3148.7/minute.
- Latency: p50=0 ms, p95=0 ms, p99=0 ms.
- Failed HTTP requests: 100%; functional checks: 0/9591.
- HTTP status counts: 200=0; non-200=3197.
- k6 response checks: 0 stub responses passed; 0 response trace headers passed; 0 upstream trace checks passed.
- This run failed; raw k6 and service logs are retained in this directory.
