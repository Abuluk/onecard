# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-messaging
- Required pinned images were already present on the target Docker engine.
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver a910689e9c545f29d019307b0345e873adca5524165da009997ff04e6113610d; broker 7d4031452ea36949bb6ceb29b26fc7e184ed47eec6bdf889134121c5c54d1920
- RocketMQ broker stop/start recovery: confirmed stopped, returned to healthy in 6s (180s bound); nameserver remained healthy.
- No Phase 1 business producer/consumer exists; this check verifies broker process recovery only.
- All services ran in Compose project onecard-business-smoke-bd25d836c2e6 with unique volumes removed after the run.
