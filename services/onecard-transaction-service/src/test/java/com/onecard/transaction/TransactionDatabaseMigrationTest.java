package com.onecard.transaction;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

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
        registry.add("spring.datasource.url", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_APP_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_APP_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_URL"));
        registry.add("spring.flyway.user", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"));
        registry.add("spring.flyway.password", () -> System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"));
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
    void runtimeUserCannotRunDdlOrReadAccountSchema() {
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "CREATE TABLE " + System.getenv("ONECARD_TRANSACTION_DB_IT_SCHEMA")
                        + ".runtime_must_not_create (id BIGINT PRIMARY KEY)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertDenied(error, "CREATE"));

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT id FROM " + System.getenv("ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA")
                        + ".private_probe", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertDenied(error, "SELECT"));
    }

    @Test
    void migrationUserCannotReadAccountSchema() {
        var migrationDataSource = new DriverManagerDataSource(
                System.getenv("ONECARD_TRANSACTION_DB_IT_URL"),
                System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_USER"),
                System.getenv("ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"));
        var migrationJdbcTemplate = new JdbcTemplate(migrationDataSource);

        assertThatThrownBy(() -> migrationJdbcTemplate.queryForObject(
                "SELECT id FROM " + System.getenv("ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA")
                        + ".private_probe", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertDenied(error, "SELECT"));
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
}
