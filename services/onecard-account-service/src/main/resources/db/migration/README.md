Account-owned Flyway migrations belong in this directory.

Use `V<version>__<description>.sql`, commit each migration with the code that
uses it, and never edit a migration after it has been applied. No business
tables exist yet, so do not add an empty versioned migration just to initialize
Flyway. The integration smoke migration is test-only and lives under
`src/test/resources/db/migration-smoke`.
