# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-data, business-messaging
- Required pinned images were already present on the target Docker engine.
- OceanBase CE 4.4.2-lts container health: healthy; tenant SELECT 1 returned 1 after 3 checks.
- OceanBase container ID: fe2ea3d614dd9daff0e4da79a4d4dcd0a2fabb1ba8e54c888e338cc9efab3d5c
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver a984464a1c2c56d786a769dee8e61a5b1e29b30f6a688fa2f01b59750ac7c5f1; broker 5ce1f5d6c7754559d96d7280f3dd2d6f6ba8d9c898ea2b94770ce20ea521a7b8
- All services ran in Compose project onecard-business-smoke-43e43f30d46c with unique volumes removed after the run.
