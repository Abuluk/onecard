package com.onecard.transaction;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ONECARD_DB_IT_ENABLED", matches = "true")
class TransactionDatabaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("service.auth-test-target-url", () -> "127.0.0.1:1");
        registry.add("spring.datasource.url", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_APP_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_APP_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_URL"));
        registry.add("spring.flyway.user", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"));
        registry.add("spring.flyway.password", () -> requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration-smoke");
        registry.add("spring.flyway.default-schema", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA"));
        registry.add("spring.flyway.schemas", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA"));
        registry.add("spring.flyway.create-schemas", () -> false);
    }

    @Test
    void springBootMigratesAndRepeatedMigrationIsAValidationOnlyRun() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT probe_value FROM migration_probe WHERE id = 1", String.class))
                .isEqualTo("migrated");

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @Test
    void runtimeUserCannotRunDdl() {
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "CREATE TABLE " + System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA")
                        + ".runtime_must_not_create (id BIGINT PRIMARY KEY)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertDenied(error, "CREATE"));
    }

    @Test
    void flywayValidationRejectsChangedMigrationChecksum(@TempDir Path tempDir) throws IOException {
        var migration = tempDir.resolve("V1__migration_probe.sql");
        Files.writeString(migration, "CREATE TABLE migration_probe (id BIGINT PRIMARY KEY, changed INT);\n");

        Flyway changed = Flyway.configure()
                .dataSource(
                        System.getenv("ONECARD_TRANSACTION_DB_IT_URL"),
                        System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"),
                        System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"))
                .locations("filesystem:" + tempDir)
                .schemas(System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA"))
                .defaultSchema(System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA"))
                .load();

        assertThatThrownBy(changed::validate).isInstanceOf(FlywayValidateException.class);
    }

    @Test
    void recoversAfterDdlSucceededButMigrationFailed(@TempDir Path tempDir) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String table = "migration_recovery_" + suffix;
        String baseTable = "migration_recovery_base_" + suffix;
        String missingTable = "migration_recovery_missing_" + suffix;
        String historyTable = "flyway_schema_history_recovery_" + suffix;
        String schema = requiredEnvironment("ONECARD_TRANSACTION_DB_IT_SCHEMA");
        Path baseMigration = tempDir.resolve("V1__migration_recovery_base.sql");
        Path failedMigration = tempDir.resolve("V2__migration_recovery_failure.sql");
        Files.writeString(baseMigration, "CREATE TABLE " + baseTable
                + " (id BIGINT PRIMARY KEY); INSERT INTO " + baseTable + " (id) VALUES (1);\n");
        Files.writeString(failedMigration, "CREATE TABLE " + table
                + " (id BIGINT PRIMARY KEY); INSERT INTO " + missingTable + " (id) VALUES (1);\n");

        Flyway recovery = migrationFlyway(tempDir, historyTable);
        try {
            assertThatThrownBy(recovery::migrate).isInstanceOf(FlywayException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, schema, baseTable)).isEqualTo(1);
            assertThat(recovery.info().all())
                    .anySatisfy(migration -> {
                        assertThat(migration.getVersion().getVersion()).isEqualTo("2");
                        assertThat(migration.getState()).isEqualTo(MigrationState.FAILED);
                    });
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, schema, table)).isIn(0, 1);

            Files.writeString(failedMigration, "CREATE TABLE IF NOT EXISTS " + table
                    + " (id BIGINT PRIMARY KEY); INSERT INTO " + table + " (id) VALUES (1);\n");
            recovery.repair();
            assertThat(recovery.info().current().getVersion().getVersion()).isEqualTo("1");
            recovery.migrate();
            assertThat(recovery.info().current().getVersion().getVersion()).isEqualTo("2");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + baseTable, Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isEqualTo(1L);
            recovery.validate();
        } finally {
            try (var connection = DriverManager.getConnection(
                    requiredEnvironment("ONECARD_TRANSACTION_DB_IT_URL"),
                    requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"),
                    requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"));
                    var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + table);
                statement.execute("DROP TABLE IF EXISTS " + baseTable);
                statement.execute("DROP TABLE IF EXISTS " + historyTable);
            }
        }
    }

    private static Flyway migrationFlyway(Path migrationDirectory, String historyTable) {
        return Flyway.configure()
                .dataSource(
                        requiredEnvironment("ONECARD_TRANSACTION_DB_IT_URL"),
                        requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"),
                        requiredEnvironment("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"))
                .locations("filesystem:" + migrationDirectory)
                .schemas(requiredEnvironment("ONECARD_TRANSACTION_DB_IT_SCHEMA"))
                .defaultSchema(requiredEnvironment("ONECARD_TRANSACTION_DB_IT_SCHEMA"))
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static void assertDenied(Throwable error, String command) {
        SQLException sqlException = findSqlException(error);
        assertThat((Object) sqlException)
                .as("OceanBase should reject the runtime user's %s privilege", command)
                .isNotNull();
        assertThat(sqlException.getErrorCode()).isIn(1044, 1142);
    }

    private static SQLException findSqlException(Throwable error) {
        Throwable current = error;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        return (SQLException) current;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required database integration environment variable is missing: " + name);
        }
        return value;
    }
}
