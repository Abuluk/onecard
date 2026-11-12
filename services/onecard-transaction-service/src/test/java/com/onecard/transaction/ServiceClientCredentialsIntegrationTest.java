package com.onecard.transaction;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ServiceClientCredentialsIntegrationTest.DownstreamProbeController.class,
        ServiceClientCredentialsIntegrationTest.DownstreamProbeSecurityConfiguration.class})
class ServiceClientCredentialsIntegrationTest {

    private static final StubServer TOKEN_SERVER = new StubServer(true);
    private static final StubServer SERVICE_SERVER = new StubServer(false);

    @Autowired
    private OAuth2ProbeClient client;

    @Autowired
    private CircuitBreakerProbeClient circuitBreakerClient;

    @Autowired
    private BulkheadProbeClient bulkheadClient;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.oauth2.enabled", () -> true);
        registry.add("spring.cloud.openfeign.oauth2.clientRegistrationId", () -> "service-client");
        registry.add("spring.cloud.openfeign.circuitbreaker.enabled", () -> true);
        registry.add("spring.security.oauth2.client.registration.service-client.provider", () -> "test-provider");
        registry.add("spring.security.oauth2.client.registration.service-client.client-id", () -> "transaction-service");
        registry.add("spring.security.oauth2.client.registration.service-client.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.registration.service-client.authorization-grant-type", () ->
                "client_credentials");
        registry.add("spring.security.oauth2.client.registration.service-client.scope", () -> "onecard.account.invoke");
        registry.add("spring.security.oauth2.client.provider.test-provider.token-uri", TOKEN_SERVER::uri);
        registry.add("service.auth-test-target-url", SERVICE_SERVER::uri);
        registry.add("spring.cloud.openfeign.client.config.service-auth-probe.connectTimeout", () -> 2000);
        registry.add("spring.cloud.openfeign.client.config.service-auth-probe.readTimeout", () -> 1500);
    }

    @AfterAll
    static void stopServers() {
        TOKEN_SERVER.close();
        SERVICE_SERVER.close();
    }

    @AfterEach
    void resetServiceResponse() throws InterruptedException {
        SERVICE_SERVER.setStatusCode(200);
        SERVICE_SERVER.setResponseDelayMillis(0);
        SERVICE_SERVER.awaitNoActiveRequests();
        SERVICE_SERVER.resetRequestCount();
    }

    @Test
    void obtainsClientCredentialsTokenAndSendsItToTheTargetService() {
        assertThat(client.probe()).isEqualTo("authenticated service call");
        assertThat(TOKEN_SERVER.requestBody()).contains("grant_type=client_credentials");
        assertThat(TOKEN_SERVER.requestBody()).contains("scope=onecard.account.invoke");
        assertThat(TOKEN_SERVER.authorizationHeader())
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("transaction-service:test-secret"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(SERVICE_SERVER.authorizationHeader()).isEqualTo("Bearer service-access-token");
    }

    @Test
    void propagatesW3cTraceparentToFeignTarget() {
        assertThat(client.probe()).isEqualTo("authenticated service call");

        assertThat(SERVICE_SERVER.traceparent())
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void doesNotConvertDownstreamFailuresIntoSuccess() {
        SERVICE_SERVER.setStatusCode(503);

        assertThatExceptionOfType(NoFallbackAvailableException.class)
                .isThrownBy(client::probe)
                .satisfies(exception -> {
                    assertThat(exception.getCause()).isInstanceOf(feign.FeignException.class);
                    assertThat(((feign.FeignException) exception.getCause()).status()).isEqualTo(503);
                    assertDownstreamFailureMapped(exception, 503, "SERVICE_UNAVAILABLE");
                });
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(1);
    }

    @Test
    void mapsDownstreamReadTimeoutToSafeGatewayTimeoutResponse() throws Exception {
        SERVICE_SERVER.setResponseDelayMillis(2500);

        var response = mockMvc.perform(get("/test/downstream-probe"))
                .andExpect(status().isGatewayTimeout())
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString())
                .contains("\"code\":\"DOWNSTREAM_TIMEOUT\"")
                .doesNotContain("FeignException", "127.0.0.1", "Read timed out");
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(1);
    }

    @Test
    void mapsDownstreamFailureToSafeHttpResponse() throws Exception {
        SERVICE_SERVER.setStatusCode(503);

        var response = mockMvc.perform(get("/test/downstream-probe"))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString())
                .contains("\"code\":\"SERVICE_UNAVAILABLE\"")
                .doesNotContain("FeignException", "authenticated service call");
    }

    @Test
    void opensCircuitAfterRepeatedDownstreamFailuresAndClosesAfterRecovery() throws Exception {
        SERVICE_SERVER.setStatusCode(503);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatExceptionOfType(NoFallbackAvailableException.class)
                    .isThrownBy(circuitBreakerClient::probe)
                    .satisfies(exception -> assertThat(exception.getCause()).isInstanceOf(feign.FeignException.class));
        }
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(5);

        Throwable openCircuit = catchThrowable(circuitBreakerClient::probe);
        assertThat(openCircuit).isInstanceOf(NoFallbackAvailableException.class);
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(5);
        assertDownstreamFailureMapped(openCircuit, 503, "SERVICE_UNAVAILABLE");

        var response = mockMvc.perform(get("/test/circuit-probe"))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse();
        assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"")
                .doesNotContain("CallNotPermittedException");
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(5);

        SERVICE_SERVER.setStatusCode(200);
        Thread.sleep(2200);
        assertThat(circuitBreakerClient.probe()).isEqualTo("authenticated service call");
        assertThat(circuitBreakerClient.probe()).isEqualTo("authenticated service call");
        assertThat(SERVICE_SERVER.requestCount()).isEqualTo(7);
    }

    @Test
    void rejectsCallsBeyondTheBoundedDownstreamConcurrency() throws Exception {
        int callCount = 33;
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        CountDownLatch ready = new CountDownLatch(callCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> calls = new ArrayList<>(callCount);
        SERVICE_SERVER.setResponseDelayMillis(500);

        try {
            for (int call = 0; call < callCount; call++) {
                calls.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return bulkheadClient.probe();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> rejectedCalls = new ArrayList<>();
            for (Future<String> call : calls) {
                try {
                    call.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    rejectedCalls.add(exception.getCause());
                }
            }

            assertThat(rejectedCalls).hasSize(1);
            assertThat(rejectedCalls.getFirst()).isInstanceOf(NoFallbackAvailableException.class);
            assertThat(rejectedCalls.getFirst().getCause())
                    .isInstanceOf(io.github.resilience4j.bulkhead.BulkheadFullException.class);
            assertDownstreamFailureMapped(rejectedCalls.getFirst(), 503, "SERVICE_UNAVAILABLE");
            assertThat(SERVICE_SERVER.requestCount()).isEqualTo(32);
            assertThat(SERVICE_SERVER.maximumConcurrentRequests()).isEqualTo(32);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void permitsHealthChecksAndDeniesUnimplementedServiceRoutes() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/transactions/commands")).andExpect(status().isForbidden());
    }

    private static void assertDownstreamFailureMapped(Throwable exception, int status, String code) {
        var response = new TransactionDownstreamExceptionHandler()
                .handleNoFallbackAvailable((NoFallbackAvailableException) exception);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).doesNotContain(exception.getMessage());
        assertThat(response.getBody().data()).isNull();
    }

    @RestController
    static class DownstreamProbeController {
        private final OAuth2ProbeClient downstreamClient;
        private final CircuitBreakerProbeClient circuitBreakerClient;

        DownstreamProbeController(OAuth2ProbeClient downstreamClient, CircuitBreakerProbeClient circuitBreakerClient) {
            this.downstreamClient = downstreamClient;
            this.circuitBreakerClient = circuitBreakerClient;
        }

        @GetMapping("/test/downstream-probe")
        String downstreamProbe() {
            return downstreamClient.probe();
        }

        @GetMapping("/test/circuit-probe")
        String circuitProbe() {
            return circuitBreakerClient.probe();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DownstreamProbeSecurityConfiguration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain downstreamProbeSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService serverExecutor = Executors.newFixedThreadPool(64);
        private final boolean tokenEndpoint;
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
        private final AtomicReference<String> traceparent = new AtomicReference<>();
        private final AtomicInteger statusCode = new AtomicInteger(200);
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maximumConcurrentRequests = new AtomicInteger();
        private final AtomicLong responseDelayMillis = new AtomicLong();

        private StubServer(boolean tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(serverExecutor);
                server.createContext("/", exchange -> {
                    if (this.tokenEndpoint) {
                        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        respond(exchange, 200, "{\"access_token\":\"service-access-token\","
                                + "\"token_type\":\"Bearer\",\"expires_in\":300}");
                        return;
                    }
                    int active = activeRequests.incrementAndGet();
                    maximumConcurrentRequests.accumulateAndGet(active, Math::max);
                    requestCount.incrementAndGet();
                    try {
                        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                        long delay = responseDelayMillis.get();
                        if (delay > 0) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        int status = "Bearer service-access-token".equals(authorizationHeader.get())
                                ? statusCode.get()
                                : 401;
                        respond(exchange, status, "authenticated service call");
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                });
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start OAuth2 test endpoint", exception);
            }
        }

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String requestBody() {
            return requestBody.get();
        }

        private String authorizationHeader() {
            return authorizationHeader.get();
        }

        private String traceparent() {
            return traceparent.get();
        }

        private void setStatusCode(int status) {
            statusCode.set(status);
        }

        private void setResponseDelayMillis(long delayMillis) {
            responseDelayMillis.set(delayMillis);
        }

        private int requestCount() {
            return requestCount.get();
        }

        private int maximumConcurrentRequests() {
            return maximumConcurrentRequests.get();
        }

        private void awaitNoActiveRequests() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (activeRequests.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (activeRequests.get() > 0) {
                throw new IllegalStateException("Stub service requests did not finish before the test reset.");
            }
        }

        private void resetRequestCount() {
            requestCount.set(0);
            maximumConcurrentRequests.set(0);
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}

