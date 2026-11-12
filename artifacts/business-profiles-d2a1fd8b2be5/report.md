# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-data, business-messaging
- Required pinned images were already present on the target Docker engine.
- OceanBase CE 4.4.2-lts container health: healthy; tenant SELECT 1 returned 1 after 1 checks.
- OceanBase container ID: 0cd3b0af76d07537fe2256527436ff048833acec3062d4253ef7e30f7a7bef9d
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver 06f4193e1868a834e1a5fe4a55453f59cd4c9d74e8bb3ef89143f05b0c851754; broker 4736902d8320b0e41a4b25fdbca901d22bcb47bae5841f8262708b8d69c62590
- RocketMQ broker stop/start recovery: confirmed stopped, returned to healthy in 6s (180s bound); nameserver remained healthy.
- No Phase 1 business producer/consumer exists; this check verifies broker process recovery only.
- All services ran in Compose project onecard-business-smoke-d2a1fd8b2be5 with unique volumes removed after the run.
