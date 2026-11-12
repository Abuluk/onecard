package com.onecard.transaction;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ONECARD_KEYCLOAK_IT_ENABLED", matches = "true")
class ServiceClientCredentialsKeycloakIntegrationTest {

    private static final StubServer SERVICE_SERVER = new StubServer();

    @Autowired
    private OAuth2ProbeClient client;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.oauth2.enabled", () -> true);
        registry.add("spring.cloud.openfeign.oauth2.clientRegistrationId", () -> "service-client");
        registry.add("spring.security.oauth2.client.registration.service-client.provider", () -> "keycloak");
        registry.add("spring.security.oauth2.client.registration.service-client.client-id",
                () -> requiredEnvironment("ONECARD_KEYCLOAK_IT_CLIENT_ID"));
        registry.add("spring.security.oauth2.client.registration.service-client.client-secret",
                () -> requiredEnvironment("ONECARD_KEYCLOAK_IT_CLIENT_SECRET"));
        registry.add("spring.security.oauth2.client.registration.service-client.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.service-client.scope",
                () -> requiredEnvironment("ONECARD_KEYCLOAK_IT_SCOPE"));
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> requiredEnvironment("ONECARD_KEYCLOAK_IT_TOKEN_URI"));
        registry.add("service.auth-test-target-url", SERVICE_SERVER::uri);
    }

    @AfterAll
    static void stopServer() {
        SERVICE_SERVER.close();
    }

    @Test
    void obtainsAndPropagatesAKeycloakTokenWithTheExpectedClaims() {
        assertThat(client.probe()).isEqualTo("authenticated service call");
        String authorization = SERVICE_SERVER.authorizationHeader();
        assertThat(authorization).startsWith("Bearer ");

        String issuer = requiredEnvironment("ONECARD_KEYCLOAK_IT_ISSUER");
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(requiredEnvironment("ONECARD_KEYCLOAK_IT_JWK_SET_URI"))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), jwt ->
                        jwt.getAudience().contains("onecard-account-service")
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Required audience is missing", null))));
        var jwt = decoder.decode(authorization.substring("Bearer ".length()));

        assertThat(jwt.getClaimAsString("scope")).contains("onecard.account.invoke");
        assertThat(SERVICE_SERVER.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required Keycloak integration environment variable is missing: " + name);
        }
        return value;
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
        private final AtomicReference<String> traceparent = new AtomicReference<>();

        private StubServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                    int status = authorizationHeader.get() != null && authorizationHeader.get().startsWith("Bearer ")
                            ? 200
                            : 401;
                    byte[] body = "authenticated service call".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start Keycloak integration test endpoint", exception);
            }
        }

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String authorizationHeader() {
            return authorizationHeader.get();
        }

        private String traceparent() {
            return traceparent.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
