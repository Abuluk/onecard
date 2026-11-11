# Scripts

Root PowerShell scripts (`ci-local.ps1`, `cd-local.ps1`, and `deploy.ps1`)
remain the Windows entry points. Add cross-platform build, migration, or
deployment automation here when the target environment is selected.

`oceanbase-account-smoke.ps1` starts a disposable OceanBase CE 4.4.2-lts
container pinned by digest and runs the account and transaction services'
Spring JDBC/Flyway integration tests against separate schemas and four
separately privileged users. It creates no persistent Docker volume and removes the container after
the run. Run it with JDK 25 and a Docker engine with at least 10 GiB available
memory; do not run it on a shared host under memory or disk pressure.
Invoke it from the repository root with `powershell -ExecutionPolicy Bypass
-File scripts/oceanbase-account-smoke.ps1`.

For the selected development server, use its Docker engine over SSH and forward
the database port back to the local JDK 25 test runner:

```powershell
$env:JAVA_HOME = "D:\Program Files\Java\jdk-25.0.4.1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
pwsh -File scripts/oceanbase-account-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41" `
  -SshTarget "admin1@192.168.0.41"
```

Remote mode creates a temporary Docker context, publishes OceanBase on the
server loopback interface only, and removes the context, SSH tunnel, and
uniquely named database container when the run ends.

`valkey-rate-limit-smoke.ps1` starts a disposable Valkey 9.1.3 instance pinned
by digest and runs Gateway HTTP tests for the 60-read and 10-write rolling
minute limits, per-subject isolation, and standard `429` responses. Run it
with JDK 25 and Docker; the remote form uses the selected development server:

```powershell
pwsh -File scripts/valkey-rate-limit-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41" `
  -SshTarget "admin1@192.168.0.41"
```

The Valkey container has no persistent volume, binds only to the server loopback
address, and is removed with its temporary Docker context and SSH tunnel.

`nacos-config-refresh-smoke.ps1` builds the account service and verifies remote
Nacos import and live refresh by publishing two temporary values to
`onecard-account-service.yaml`. Its config probe is disabled by default and only
exists with the `dev` profile. The script removes its Nacos config, containers,
volume, image tag, and temporary Docker context.

```powershell
pwsh -File scripts/nacos-config-refresh-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41" `
  -SshTarget "admin1@192.168.0.41"
```

`phase1-oidc-performance-smoke.ps1` creates an isolated Keycloak 26.8.1 realm
with a one-hour access-token lifetime for the duration of the test, real
Gateway and account-service containers, and a loopback-only stub upstream on
the development server. It verifies user and service JWT claims, account scope
enforcement, transaction-service client credentials, trace propagation, and a
paced k6 load of 3,200 requests per minute at 50/100/250/500 VUs. The 500-VU
stage runs for 30 minutes by default. Each stage enforces HTTP errors below 1%,
p95 below 500 ms, and checks above 99%; these are development test thresholds,
not production SLOs. Remote mode runs the pinned k6 2.3.0 Linux binary on the
development server after verifying its SHA-256, so load traffic does not cross
the SSH tunnel. Reports and raw metrics, including load-generator resources,
are written to a unique directory under `artifacts/`.

```powershell
pwsh -File scripts/phase1-oidc-performance-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41" `
  -SshTarget "admin1@192.168.0.41" `
  -Duration 30m
```

The PR workflow also imports `tests/keycloak/onecard-dev-realm.json` into an
isolated Keycloak container, generates a one-run service client secret, and
runs `ServiceClientCredentialsKeycloakIntegrationTest`. It removes Keycloak at
the end of the step group and uploads its startup log only on failure.

`prod-config-fail-closed-smoke.ps1` builds all eight services and the Gateway,
then starts each with the `prod` profile pointed at an unused loopback Nacos
port. All nine processes must exit unsuccessfully within 60 seconds and identify
the missing Nacos configuration in their logs. It does not connect to or alter
the shared Nacos server.

`database-fail-closed-smoke.ps1` explicitly enables Flyway for account and
transaction, disables optional Nacos imports, and points each service at an
unused loopback database port. Both applications must fail startup with a
database connectivity/migration error rather than run without their configured
database.

`platform-recovery-smoke.ps1` builds and starts all nine applications in an
isolated Compose project, verifies their Nacos registrations, restarts Nacos
and the account service, and checks that the Gateway returns `401` for an
anonymous protected request. It removes the project volumes, images, temporary
Docker context, and SSH tunnel. Run it against the selected development server
with:

```powershell
pwsh -File scripts/platform-recovery-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41" `
  -SshTarget "admin1@192.168.0.41"
```

`business-profiles-smoke.ps1` starts the optional OceanBase and RocketMQ
profiles in an isolated project, waits for all three health checks, and runs
`SELECT 1` against OceanBase. It removes the temporary volumes and Docker
context after the run.

```powershell
pwsh -File scripts/business-profiles-smoke.ps1 `
  -DockerHost "ssh://admin1@192.168.0.41"
```
