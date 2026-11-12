# CI and Image Publishing

## Continuous integration

`.github/workflows/ci.yml` runs the full Maven `clean verify` build on pull
requests and pushes to `main` or `develop`. The job token has read-only
repository access and does not receive image-publishing credentials. Surefire
XML reports are retained as a workflow artifact for 14 days, including when
the build fails. Both the default local Compose manifest and its opt-in debug
override are parsed on every run with a non-secret validation-only Nacos token.
After Maven verification, CI builds all nine application images and blocks on
Trivy HIGH/CRITICAL OS or library vulnerabilities, including unfixed findings.
It then starts the already-built account image with Nacos, queries the Nacos
3.x v3 client API, and requires a healthy/enabled `SPRING_CLOUD` instance on
port 8082. CI restarts Nacos and requires the account client to register again
within 60 seconds. The fresh Compose project and Nacos volume are removed even
when either registration check fails.
The parallel `secret-scan` job runs Gitleaks over the full checkout history;
PR comments and secret-bearing scan artifacts are disabled.
`.gitleaksignore` contains only two exact historical fingerprints previously
accepted by the repository owner; new commits and other matches remain scanned.
`.github/workflows/dependency-review.yml` blocks pull requests that add a
high- or critical-severity vulnerable dependency. Dependabot checks Maven and
GitHub Actions updates weekly. Maven verification includes a transaction
service integration test for OAuth2 client-credentials token acquisition and
default-deny service paths.

Configure branch protection for `main` and `develop` to require the `build`,
`secret-scan`, and `dependency-review` jobs before merging. Repository-level
Dependabot alerts and the actual remote workflow runs still require setup and
verification in GitHub.

## Manual image publishing

`.github/workflows/deploy.yml` only runs when manually dispatched. Select one
of `gateway`, `account`, `transaction`, `payment-adapter`, `clearing`,
`notification`, `risk`, `access`, or `device`. It verifies that Maven module
and publishes `onecard-<module>` with the commit SHA and `latest` tags.

Configure repository secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` before
running the workflow. The workflow does not publish on ordinary pushes or pull
requests.

## Local platform smoke

`.github/workflows/performance-gate.yml` starts Nacos and the account service
from the local Compose file with the explicit debug port override, verifies
the account's healthy `SPRING_CLOUD` instance through the Nacos 3.x v3 client
API, and runs `tests/performance/smoke.js` against
`http://localhost:8082`. The workflow accepts no caller-supplied URL and
retains the service log for 14 days. This checks startup, registration, and
health only; it is not a platform performance or capacity result. PR CI also
runs the Gateway HTTP-upstream proxy, authentication, route-boundary, and
management-port integration tests through Maven `clean verify`.

The default Compose stack does not publish business service ports. The debug
override is opt-in and binds those ports to loopback for local development.
