# Production required Nacos config smoke

- Target: all eight service and Gateway production profiles.
- Nacos endpoint: a local ephemeral port with no listener.
- Result: gateway: exited 1 with Nacos config failure; account: exited 1 with Nacos config failure; transaction: exited 1 with Nacos config failure; access: exited 1 with Nacos config failure; clearing: exited 1 with Nacos config failure; device: exited 1 with Nacos config failure; notification: exited 1 with Nacos config failure; payment-adapter: exited 1 with Nacos config failure; risk: exited 1 with Nacos config failure.
- Logs are in this directory.
