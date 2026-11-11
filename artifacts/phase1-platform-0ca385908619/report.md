# Phase 1 Gateway Platform Smoke (failed)

- Failed stage: 500 VUs for 10s; target pacing is 3,200 requests/minute.
- Requests: 10920; achieved rate: 16378.9/minute.
- Latency: p50=0 ms, p95=0 ms, p99=92.1 ms.
- Failed HTTP requests: 97.344%; functional checks: 870/32760.
- HTTP status counts: 200=290; non-200=10630.
- k6 response checks: 290 stub responses passed; 290 response trace headers passed; 290 upstream trace checks passed.
- This run failed; raw k6 and service logs are retained in this directory.
