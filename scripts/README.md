# Scripts

Root PowerShell scripts (`ci-local.ps1`, `cd-local.ps1`, and `deploy.ps1`)
remain the Windows entry points. Add cross-platform build, migration, or
deployment automation here when the target environment is selected.

`oceanbase-account-smoke.ps1` starts a disposable OceanBase CE 4.4.2.1
container pinned by digest and runs the account and transaction services'
Spring JDBC/Flyway integration tests against separate schemas and four
separately privileged users. It creates no persistent Docker volume and removes the container after
the run. Run it with JDK 25 and a Docker engine with at least 10 GiB available
memory; do not run it on a shared host under memory or disk pressure.
Invoke it from the repository root with `powershell -ExecutionPolicy Bypass
-File scripts/oceanbase-account-smoke.ps1`.
