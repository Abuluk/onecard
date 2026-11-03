# CI and Image Publishing

## Continuous integration

`.github/workflows/ci.yml` runs the full Maven `clean verify` build on pull
requests and pushes to `main` or `develop`. The job token has read-only
repository access and does not receive image-publishing credentials. Surefire
XML reports are retained as a workflow artifact for 14 days, including when
the build fails.

Configure branch protection for `main` and `develop` to require the `build`
job before merging. Repository-level dependency alerts, secret scanning,
container image scanning, and their blocking severity still require setup and
review in GitHub; the CI workflow does not claim those checks have passed.

## Manual image publishing

`.github/workflows/deploy.yml` only runs when manually dispatched. Select one
of `gateway`, `account`, `transaction`, `payment-adapter`, `clearing`,
`notification`, `risk`, `access`, or `device`. It verifies that Maven module
and publishes `onecard-<module>` with the commit SHA and `latest` tags.

Configure repository secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` before
running the workflow. The workflow does not publish on ordinary pushes or pull
requests.

## Local platform smoke

`.github/workflows/performance-gate.yml` starts the account service from the
local Compose file and runs `tests/performance/smoke.js` against
`http://localhost:8082`. The workflow accepts no caller-supplied URL and
retains the service log for 14 days. This checks startup and health only; it is
not a platform performance or capacity result.
