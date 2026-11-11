# Database unavailable fail-closed smoke

- Target: account and transaction services with Flyway explicitly enabled.
- Database endpoint: per-service ephemeral loopback port with no listener.
- Result: account: exited 1 with database migration/connectivity failure; transaction: exited 1 with database migration/connectivity failure.
- Startup logs are in this directory.
