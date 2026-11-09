package com.onecard.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=19081"})
@ActiveProfiles("test")
class GatewaySecurityIntegrationTest {

    private static final String ISSUER = "http://issuer.test/realms/onecard-dev";
    private static final String AUDIENCE = "onecard-api";
    private static final String KID = "phase1-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final KeyPair WRONG_SIGNING_KEY = generateKeyPair();
    private static final JwksServer JWKS_SERVER = new JwksServer(SIGNING_KEY);
    private static final StubServer STUB_SERVER = new StubServer();

    @LocalServerPort
    private int serverPort;

    private static final int MANAGEMENT_PORT = 19081;

    private WebTestClient client;

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("onecard.security.issuer-uri", () -> ISSUER);
        registry.add("onecard.security.audience", () -> AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS_SERVER::uri);
        registry.add("onecard.security.roles.user", () -> "onecard-user");
        registry.add("onecard.security.roles.admin", () -> "onecard-admin");
        registry.add("onecard.security.roles.auditor", () -> "onecard-auditor");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", STUB_SERVER::uri);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/account/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "test-clearing-upstream");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri", STUB_SERVER::uri);
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/api/clearing/**");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.close();
        STUB_SERVER.close();
    }

    @BeforeEach
    void createClient() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + serverPort).build();
    }

    @Test
    void rejectsRequestsWithoutBearerToken() {
        client.get().uri("/api/account/balance").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithInvalidSignature() {
        String token = token(WRONG_SIGNING_KEY, ISSUER, AUDIENCE, "onecard-user", Instant.now().plusSeconds(60));

        getWithToken("/api/account/balance", token).expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        String token = token(SIGNING_KEY, "https://wrong-issuer.test", AUDIENCE, "onecard-user", Instant.now().plusSeconds(60));

        getWithToken("/api/account/balance", token).expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        String token = token(SIGNING_KEY, ISSUER, "another-api", "onecard-user", Instant.now().plusSeconds(60));

        getWithToken("/api/account/balance", token).expectStatus().isUnauthorized();
    }

    @Test
    void rejectsExpiredToken() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-user", Instant.now().minusSeconds(60));

        getWithToken("/api/account/balance", token).expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRoleInsufficientForClearingWrite() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-auditor", Instant.now().plusSeconds(60));

        client.post().uri("/api/clearing/batches").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void rejectsUnlistedInternalRoutesEvenForAdministrator() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));

        getWithToken("/api/risk/rules", token).expectStatus().isForbidden();
        getWithToken("/api/devices/commands", token).expectStatus().isForbidden();
        getWithToken("/api/access/policies", token).expectStatus().isForbidden();
    }

    @Test
    void rejectsUnsupportedMethodsOnAllowlistedRoutes() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));

        client.put().uri("/api/account/cards/1").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void rejectsUnknownAndPaymentCallbackRoutesUntilCallbackVerificationExists() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));

        getWithToken("/api/unknown/resource", token).expectStatus().isForbidden();
        client.post().uri("/api/payments/callbacks/wechat").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get().uri("/api/payments/callback/wechat").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void rejectsPathPrefixLookalikes() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));

        getWithToken("/api/accounting/balance", token).expectStatus().isForbidden();
        client.get().uri(URI.create("http://127.0.0.1:" + serverPort + "/api/account/%2e%2e/risk/rules"))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void rejectsActuatorThroughPublicGatewayPort() {
        client.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();

        String adminToken = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));
        getWithToken("/actuator/health", adminToken).expectStatus().isForbidden();
    }

    @Test
    void servesOnlyHealthAndPrometheusFromManagementPort() {
        WebTestClient managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + MANAGEMENT_PORT)
                .build();

        managementClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
        managementClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
        managementClient.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void forwardsAllowedUserTokenAfterRemovingForgedIdentityHeaders() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-user", Instant.now().plusSeconds(60));

        client.get().uri("/api/account/balance")
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("X-User-Id", "forged-user");
                    headers.set("X-User-Roles", "onecard-admin");
                    headers.set("X-Authenticated-User", "forged-user");
                })
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.userId")
                .isEqualTo("")
                .jsonPath("$.userRoles")
                .isEqualTo("")
                .jsonPath("$.authenticatedUser")
                .isEqualTo("");
    }

    @Test
    void authorizesAdministratorForReadOnlyClearingRoute() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-admin", Instant.now().plusSeconds(60));

        client.get().uri("/api/clearing/batches").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void authorizesAuditorForReadOnlyClearingRoute() {
        String token = token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard-auditor", Instant.now().plusSeconds(60));

        client.get().uri("/api/clearing/batches").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private WebTestClient.ResponseSpec getWithToken(String path, String token) {
        return client.get().uri(path).headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private static String token(KeyPair keyPair, String issuer, String audience, String role, Instant expiresAt) {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KID + "\"}");
        String claims = "{\"iss\":\"" + issuer + "\",\"sub\":\"test-user\",\"aud\":[\"" + audience
                + "\"],\"iat\":" + now + ",\"exp\":" + expiresAt.getEpochSecond()
                + ",\"realm_access\":{\"roles\":[\"" + role + "\"]}}";
        String unsignedToken = header + "." + base64Url(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(unsignedToken.getBytes(StandardCharsets.US_ASCII));
            return unsignedToken + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign test token", exception);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create test signing key", exception);
        }
    }

    private static final class JwksServer implements AutoCloseable {
        private final HttpServer server;

        private JwksServer(KeyPair keyPair) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks", exchange -> {
                    byte[] body = jwks(keyPair).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start test JWKS endpoint", exception);
            }
        }

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        private static String jwks(KeyPair keyPair) {
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + KID
                    + "\",\"n\":\"" + unsignedBase64Url(publicKey.getModulus()) + "\",\"e\":\""
                    + unsignedBase64Url(publicKey.getPublicExponent()) + "\"}]}";
        }

        private static String unsignedBase64Url(BigInteger value) {
            byte[] bytes = value.toByteArray();
            int offset = bytes[0] == 0 ? 1 : 0;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOfRange(bytes, offset, bytes.length));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        private StubServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    String body = "{\"userId\":\"" + header(exchange, "X-User-Id")
                            + "\",\"userRoles\":\"" + header(exchange, "X-User-Roles")
                            + "\",\"authenticatedUser\":\"" + header(exchange, "X-Authenticated-User") + "\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(bytes);
                    }
                });
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start test upstream", exception);
            }
        }

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
            String value = exchange.getRequestHeaders().getFirst(name);
            return value == null ? "" : value;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
