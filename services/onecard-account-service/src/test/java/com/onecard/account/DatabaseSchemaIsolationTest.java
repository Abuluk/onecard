package com.onecard.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ONECARD_DB_IT_ISOLATION_ENABLED", matches = "true")
class DatabaseSchemaIsolationTest {

    @DynamicPropertySource
    static void accountDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("onecard.security.issuer-uri", () -> "http://issuer.test/realms/onecard-dev");
        registry.add("onecard.security.audience", () -> "onecard-account-service");
        registry.add("onecard.security.required-scope", () -> "onecard.account.invoke");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/jwks");
        registry.add("spring.datasource.url", () -> requiredEnvironment("ONECARD_DB_IT_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("ONECARD_DB_IT_APP_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("ONECARD_DB_IT_APP_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> requiredEnvironment("ONECARD_DB_IT_URL"));
        registry.add("spring.flyway.user", () -> requiredEnvironment("ONECARD_DB_IT_MIGRATION_USER"));
        registry.add("spring.flyway.password", () -> requiredEnvironment("ONECARD_DB_IT_MIGRATION_PASSWORD"));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration-smoke");
        registry.add("spring.flyway.default-schema", () -> requiredEnvironment("ONECARD_DB_IT_SCHEMA"));
        registry.add("spring.flyway.schemas", () -> requiredEnvironment("ONECARD_DB_IT_SCHEMA"));
        registry.add("spring.flyway.create-schemas", () -> false);
    }

    @Test
    void applicationUsersCannotReadAnotherServiceSchema() {
        assertCannotRead(
                "ONECARD_DB_IT_URL",
                "ONECARD_DB_IT_APP_USER",
                "ONECARD_DB_IT_APP_PASSWORD",
                "ONECARD_DB_IT_OTHER_SCHEMA");
        assertCannotRead(
                "ONECARD_TRANSACTION_DB_IT_URL",
                "ONECARD_TRANSACTION_DB_IT_APP_USER",
                "ONECARD_TRANSACTION_DB_IT_APP_PASSWORD",
                "ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA");
    }

    @Test
    void migrationUsersCannotReadAnotherServiceSchema() {
        assertCannotRead(
                "ONECARD_DB_IT_URL",
                "ONECARD_DB_IT_MIGRATION_USER",
                "ONECARD_DB_IT_MIGRATION_PASSWORD",
                "ONECARD_DB_IT_OTHER_SCHEMA");
        assertCannotRead(
                "ONECARD_TRANSACTION_DB_IT_URL",
                "ONECARD_TRANSACTION_DB_IT_MIGRATION_USER",
                "ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD",
                "ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA");
    }

    private static void assertCannotRead(String urlVariable, String userVariable, String passwordVariable,
            String otherSchemaVariable) {
        var jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                requiredEnvironment(urlVariable),
                requiredEnvironment(userVariable),
                requiredEnvironment(passwordVariable)));

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT id FROM " + requiredEnvironment(otherSchemaVariable) + ".private_probe", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertDenied(error, "SELECT"));
    }

    private static void assertDenied(Throwable error, String command) {
        SQLException sqlException = findSqlException(error);
        assertThat((Object) sqlException)
                .as("OceanBase should reject this account's %s privilege", command)
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
