package com.onecard.transaction;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceClientCredentialsIntegrationTest {

    private static final StubServer TOKEN_SERVER = new StubServer(true);
    private static final StubServer SERVICE_SERVER = new StubServer(false);

    @Autowired
    private OAuth2ProbeClient client;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.oauth2.enabled", () -> true);
        registry.add("spring.cloud.openfeign.oauth2.clientRegistrationId", () -> "service-client");
        registry.add("spring.security.oauth2.client.registration.service-client.provider", () -> "test-provider");
        registry.add("spring.security.oauth2.client.registration.service-client.client-id", () -> "transaction-service");
        registry.add("spring.security.oauth2.client.registration.service-client.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.registration.service-client.authorization-grant-type", () ->
                "client_credentials");
        registry.add("spring.security.oauth2.client.registration.service-client.scope", () -> "onecard.account.invoke");
        registry.add("spring.security.oauth2.client.provider.test-provider.token-uri", TOKEN_SERVER::uri);
        registry.add("service.auth-test-target-url", SERVICE_SERVER::uri);
    }

    @AfterAll
    static void stopServers() {
        TOKEN_SERVER.close();
        SERVICE_SERVER.close();
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
    void permitsHealthChecksAndDeniesUnimplementedServiceRoutes() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/transactions/commands")).andExpect(status().isForbidden());
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final boolean tokenEndpoint;
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
        private final AtomicReference<String> traceparent = new AtomicReference<>();

        private StubServer(boolean tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    if (this.tokenEndpoint) {
                        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        respond(exchange, 200, "{\"access_token\":\"service-access-token\","
                                + "\"token_type\":\"Bearer\",\"expires_in\":300}");
                        return;
                    }
                    authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                    int status = "Bearer service-access-token".equals(authorizationHeader.get()) ? 200 : 401;
                    respond(exchange, status, "authenticated service call");
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
        }
    }
}

