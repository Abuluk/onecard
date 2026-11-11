package com.onecard.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=19082")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ONECARD_VALKEY_IT_ENABLED", matches = "true")
class GatewayValkeyRateLimitIntegrationTest {

    private static final String ISSUER = "http://issuer.test/realms/onecard-dev";
    private static final String AUDIENCE = "onecard-api";
    private static final String KID = "valkey-rate-limit-test";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final JwksServer JWKS_SERVER = new JwksServer(SIGNING_KEY);
    private static final StubServer STUB_SERVER = new StubServer();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    private RedisScript<Long> rateLimitScript;

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("onecard.rate-limit.enabled", () -> true);
        registry.add("onecard.security.issuer-uri", () -> ISSUER);
        registry.add("onecard.security.audience", () -> AUDIENCE);
        registry.add("onecard.security.roles.user", () -> "onecard-user");
        registry.add("onecard.security.roles.admin", () -> "onecard-admin");
        registry.add("onecard.security.roles.auditor", () -> "onecard-auditor");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS_SERVER::uri);
        registry.add("spring.data.redis.url", GatewayValkeyRateLimitIntegrationTest::requiredValkeyUrl);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "test-account-upstream");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", STUB_SERVER::uri);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/account/**");
    }

    @AfterAll
    static void stopServers() {
        JWKS_SERVER.close();
        STUB_SERVER.close();
    }

    @BeforeEach
    void createClient() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + serverPort).build();
    }

    @Test
    void rateLimitScriptReturnsAnIntegerFromValkey() {
        var result = redisTemplate.execute(rateLimitScript,
                        java.util.List.of("onecard:gateway:rate-limit:it:" + UUID.randomUUID()),
                        "60000", "60", UUID.randomUUID().toString())
                .collectList()
                .block();

        assertThat(result).containsExactly(1L);
    }

    @Test
    void enforcesSixtyReadsPerRollingMinutePerSubject() {
        String subject = "read-" + UUID.randomUUID();
        String token = token(subject);
        for (int request = 0; request < 60; request++) {
            client.get().uri("/api/account/balance").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isOk();
        }

        client.get().uri("/api/account/balance").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.message").isEqualTo("Too many requests");

        client.get().uri("/api/account/balance").headers(headers -> headers.setBearerAuth(token(subject + "-other")))
                .exchange().expectStatus().isOk();
    }

    @Test
    void enforcesTenWritesPerRollingMinutePerSubject() {
        String token = token("write-" + UUID.randomUUID());
        for (int request = 0; request < 10; request++) {
            client.post().uri("/api/account/probe").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isOk();
        }

        client.post().uri("/api/account/probe").headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.message").isEqualTo("Too many requests");
    }

    @Test
    void expiresOnlyEntriesOutsideTheRollingMinuteWindow() {
        String key = "onecard:gateway:rate-limit:v1:read:" + UUID.randomUUID();
        RedisScript<Long> serverTimeScript = new DefaultRedisScript<>(
                "local t = redis.call('TIME'); return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
                Long.class);
        long now = redisTemplate.execute(serverTimeScript, List.of()).next().block();

        for (int request = 0; request < 30; request++) {
            redisTemplate.opsForZSet().add(key, "expired-" + request, now - 60_001).block();
            redisTemplate.opsForZSet().add(key, "recent-" + request, now - 30_000).block();
        }

        var first = redisTemplate.execute(rateLimitScript, List.of(key), "60000", "60", UUID.randomUUID().toString())
                .next().block();
        assertThat(first).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().size(key).block()).isEqualTo(31L);

        for (int request = 1; request < 30; request++) {
            var allowed = redisTemplate.execute(rateLimitScript, List.of(key), "60000", "60",
                    UUID.randomUUID().toString()).next().block();
            assertThat(allowed).isEqualTo(1L);
        }
        var denied = redisTemplate.execute(rateLimitScript, List.of(key), "60000", "60",
                UUID.randomUUID().toString()).next().block();

        assertThat(denied).isEqualTo(0L);
    }

    @Test
    void sharesTheAtomicSubjectBucketAcrossConcurrentGatewayInstances() {
        String subject = "clustered-" + UUID.randomUUID();
        String token = token(subject);
        String valkeyUrl = requiredValkeyUrl();
        ConfigurableApplicationContext secondGateway = new SpringApplicationBuilder(GatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--spring.profiles.active=test",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--onecard.rate-limit.enabled=true",
                        "--onecard.security.issuer-uri=" + ISSUER,
                        "--onecard.security.audience=" + AUDIENCE,
                        "--onecard.security.roles.user=onecard-user",
                        "--onecard.security.roles.admin=onecard-admin",
                        "--onecard.security.roles.auditor=onecard-auditor",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + JWKS_SERVER.uri(),
                        "--spring.data.redis.url=" + valkeyUrl,
                        "--spring.cloud.gateway.server.webflux.routes[0].id=test-account-upstream",
                        "--spring.cloud.gateway.server.webflux.routes[0].uri=" + STUB_SERVER.uri(),
                        "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/account/**");

        try (secondGateway) {
            int secondPort = ((WebServerApplicationContext) secondGateway).getWebServer().getPort();
            WebTestClient secondClient = WebTestClient.bindToServer()
                    .baseUrl("http://127.0.0.1:" + secondPort)
                    .build();
            ExecutorService executor = Executors.newFixedThreadPool(32);
            try {
                List<CompletableFuture<Integer>> requests = IntStream.range(0, 120)
                        .mapToObj(request -> CompletableFuture.supplyAsync(() -> {
                            WebTestClient gateway = request % 2 == 0 ? client : secondClient;
                            return gateway.get().uri("/api/account/balance")
                                    .headers(headers -> headers.setBearerAuth(token))
                                    .exchange()
                                    .returnResult(String.class)
                                    .getStatus()
                                    .value();
                        }, executor))
                        .toList();
                List<Integer> statuses = requests.stream().map(CompletableFuture::join).toList();

                assertThat(statuses).filteredOn(status -> status == 200).hasSize(60);
                assertThat(statuses).filteredOn(status -> status == 429).hasSize(60);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static String token(String subject) {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KID + "\"}");
        String claims = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + subject + "\",\"aud\":[\""
                + AUDIENCE + "\"],\"iat\":" + now + ",\"exp\":" + (now + 600)
                + ",\"realm_access\":{\"roles\":[\"onecard-user\"]}}";
        String unsignedToken = header + "." + base64Url(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(SIGNING_KEY.getPrivate());
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

    private static String requiredValkeyUrl() {
        String value = System.getenv("ONECARD_VALKEY_IT_URL");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ONECARD_VALKEY_IT_URL is required for Valkey integration tests");
        }
        return value;
    }

    private static final class JwksServer implements AutoCloseable {
        private final HttpServer server;

        private JwksServer(KeyPair keyPair) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks", exchange -> {
                    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
                    String body = "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
                            + KID + "\",\"n\":\"" + unsignedBase64Url(publicKey.getModulus())
                            + "\",\"e\":\"" + unsignedBase64Url(publicKey.getPublicExponent()) + "\"}]}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(bytes);
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
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
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

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
