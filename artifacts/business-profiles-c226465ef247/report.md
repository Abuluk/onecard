# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-data, business-messaging
- OceanBase CE 4.4.2-lts container health: healthy; SELECT 1 returned 1.
- OceanBase container ID: a50cebd49c1b40ba5cd0cf7c016b716c560c767d618e1095587fcbd5c490575f
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver a131f3d059cf7083798a742a74c1d8805ce14f6fddfa47e4e9abda40fb93bcb8; broker 5d5615ea02821aba1e735b65f32955c1fb63243466712e834bcc7d13d6879847
- All services ran in Compose project onecard-business-smoke-c226465ef247 with unique volumes removed after the run.
