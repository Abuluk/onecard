# Phase 1 Network Boundary Smoke

- Target Docker Engine: `admin1@192.168.0.41` (Docker Engine 29.6.2).
- An ephemeral Valkey container on an `internal: true` bridge did not receive its requested `127.0.0.1:18081` host binding: Docker reported `18081/tcp` only and `NetworkSettings.Ports["18081/tcp"]` was `null`.
- Control container on a regular bridge received `127.0.0.1:18082->18082/tcp`; inspection reported `HostIp=127.0.0.1`, `HostPort=18082`.
- Both target probe containers and networks were removed.
- After adding the Gateway-only `onecard-ingress` bridge, a local Docker Desktop runtime probe attached one container to both an internal private network and a separate ingress bridge. The loopback host binding and TCP reachability both passed.
- Default, optional business profile, and debug Compose configurations parse successfully. Local assertions verify that all OneCard services remain on `onecard-private`, no non-Gateway business service publishes a default host port, and the debug override publishes exactly nine application ports on `127.0.0.1`.
- After SSH access returned, the corrected topology passed the target recovery smoke at `artifacts/platform-recovery-e457d3c619/report.md`: Gateway loopback publication, nine healthy applications, 9/9 Nacos registrations after whole-stack recovery, Nacos/account restarts, and anonymous HTTP 401.
- The updated recovery smoke also tested direct TCP access from this workstation to `192.168.0.41:8081`; it could not connect while Docker inspection confirmed `HostIp=127.0.0.1`. Latest evidence: `artifacts/platform-recovery-92640a815c/report.md`.
- The combined OceanBase/RocketMQ profile passed after the network change at `artifacts/business-profiles-37bfda1b29eb/report.md`; OceanBase returned `SELECT 1 = 1` and RocketMQ nameserver/broker were healthy.
