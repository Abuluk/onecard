# Business Profile Smoke Failure

- OceanBase and RocketMQ container health checks passed on the target.
- The first `obclient SELECT 1` immediately after Compose reported OceanBase healthy returned `ERROR 8001 (08004): Server is initializing`.
- The smoke script originally treated the first SQL attempt as final and failed. The tenant query is now retried up to 120 times at two-second intervals and still must return exactly `1`.
- The corrected combined-profile run passed: `artifacts/business-profiles-37bfda1b29eb/report.md`.
- The failed run removed its temporary containers, volumes, network, and Docker context.
