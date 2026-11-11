# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-data, business-messaging
- Required pinned images were already present on the target Docker engine.
- OceanBase CE 4.4.2-lts container health: healthy; tenant SELECT 1 returned 1 after 1 checks.
- OceanBase container ID: 414a9156913a876d96124c24f01f5f58056cb8f5654f72a9b5011d8efcd03296
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver 4c1bca8bcda146ceb8c0045a27e1cdb873182db8c1cfddd220e250e679076714; broker 1ef276a0757df6f0f2dbcec633dd21c3c120a0c91fd628d5e1788a8d5a2e89b6
- All services ran in Compose project onecard-business-smoke-37bfda1b29eb with unique volumes removed after the run.
