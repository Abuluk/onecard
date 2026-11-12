# Phase 1 Business Platform Profiles Smoke

- Target: ssh://admin1@192.168.0.41
- Profiles: business-messaging
- Transferred and verified pinned images from local Docker context desktop-linux: apache/rocketmq:5.3.1@sha256:d2b231c1b9204129e4f4dd65ec1521c81b8d6826e5f1fe8daa521dab0db5bf16.
- RocketMQ 5.3.1 nameserver and broker health: healthy.
- RocketMQ container IDs: nameserver ff6d99ac62e5959476b9996fe5281e4deeb6ed7f4c938af4db45661f1f25437d; broker d8c9827afc8c3c09a19e93c62721461ccef9b280d6173eb32de4e869ff419b83
- All services ran in Compose project onecard-business-smoke-60da3e2ac759 with unique volumes removed after the run.
