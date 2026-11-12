# Phase 1 Gateway Platform Smoke (failed)

- Failed stage: 50 VUs for 1m; target pacing is 3,200 requests/minute.
- Requests: 3199; achieved rate: 3149.8/minute.
- Latency: p50=45.3 ms, p95=91.2 ms, p99=117.8 ms.
- Failed HTTP requests: 0%; functional checks: 3199/9597.
- HTTP status counts: 200=3199; non-200=0.
- k6 response checks: 3199 stub responses passed; 0 response trace headers passed; 0 upstream trace checks passed.
- This run failed; raw k6 and service logs are retained in this directory.
