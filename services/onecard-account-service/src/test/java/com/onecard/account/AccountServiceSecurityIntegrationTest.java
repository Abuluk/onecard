package com.onecard.account;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.onecard.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.RequestBody;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(AccountServiceSecurityIntegrationTest.ProbeController.class)
class AccountServiceSecurityIntegrationTest {

    private static final String ISSUER = "http://issuer.test/realms/onecard-dev";
    private static final String AUDIENCE = "onecard-account-service";
    private static final String SCOPE = "onecard.account.invoke";
    private static final String KEY_ID = "account-service-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final KeyPair WRONG_SIGNING_KEY = generateKeyPair();
    private static final JwksServer JWKS_SERVER = new JwksServer(SIGNING_KEY);

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("onecard.security.issuer-uri", () -> ISSUER);
        registry.add("onecard.security.audience", () -> AUDIENCE);
        registry.add("onecard.security.required-scope", () -> SCOPE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS_SERVER::uri);
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.close();
    }

    @Test
    void requiresServiceIdentityForInternalApi() throws Exception {
        mockMvc.perform(get("/test/service-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    void acceptsOnlySignedTokensWithTheAccountAudienceAndScope() throws Exception {
        requestWithToken(token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60)))
                .andExpect(status().isOk());
        requestWithToken(token(SIGNING_KEY, ISSUER, AUDIENCE, "onecard.risk.invoke", Instant.now().plusSeconds(60)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Access is denied"));
        requestWithToken(token(SIGNING_KEY, ISSUER, "onecard-risk-service", SCOPE, Instant.now().plusSeconds(60)))
                .andExpect(status().isUnauthorized());
        requestWithToken(token(SIGNING_KEY, "https://wrong-issuer.test", AUDIENCE, SCOPE,
                        Instant.now().plusSeconds(60)))
                .andExpect(status().isUnauthorized());
        requestWithToken(token(WRONG_SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60)))
                .andExpect(status().isUnauthorized());
        requestWithToken(token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().minusSeconds(60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leavesHealthPublicButProtectsOtherManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer "
                        + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/dev/config-probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/dev/service-auth-probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void exposesOnlyApprovedLowCardinalityPlatformMetrics() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains("http_server_requests_seconds_count")
                .doesNotContain("disk_free_bytes", "disk_total_bytes", "traceparent", "Authorization");
    }

    @Test
    void recordsW3cTraceIdInMdc() throws Exception {
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentId = "0123456789abcdef";
        String traceparent = "00-" + traceId + "-" + parentId + "-01";

        var result = mockMvc.perform(get("/test/trace-context")
                        .header("Authorization", "Bearer "
                                + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60)))
                        .header("traceparent", traceparent))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo(traceId);
        String[] responseTraceparent = result.getResponse().getHeader("traceparent").split("-");
        assertThat(responseTraceparent).hasSize(4);
        assertThat(responseTraceparent[0]).isEqualTo("00");
        assertThat(responseTraceparent[1]).isEqualTo(traceId);
        assertThat(responseTraceparent[2]).isNotEqualTo(parentId);
        assertThat(responseTraceparent[3]).matches("[0-9a-f]{2}");
    }

    @Test
    void rejectedServiceRequestReturnsAndAuditsTrustedTraceId(CapturedOutput output) throws Exception {
        String traceId = "0123456789abcdef0123456789abcdef";
        String traceparent = "00-" + traceId + "-0123456789abcdef-01";

        var result = mockMvc.perform(get("/test/service-auth").header("traceparent", traceparent))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(result.getResponse().getHeader("traceparent").split("-")[1]).isEqualTo(traceId);
        assertThat(output.getOut()).contains("security_event event=authentication_failed "
                + "service=onecard-account-service method=GET");
        assertThat(output.getOut()).contains(traceId);
    }

    @Test
    void replacesMalformedW3cTraceparent() throws Exception {
        var result = mockMvc.perform(get("/test/trace-context")
                        .header("Authorization", "Bearer "
                                + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60)))
                        .header("traceparent", "malformed"))
                .andExpect(status().isOk())
                .andReturn();

        String traceId = result.getResponse().getContentAsString();
        assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("malformed");
        assertThat(result.getResponse().getHeader("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                .startsWith("00-" + traceId + "-");
    }

    @Test
    void returnsSharedBusinessErrorContract() throws Exception {
        mockMvc.perform(get("/test/business-error").header("Authorization", "Bearer "
                        + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEMO_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Demo request could not be completed"));
    }

    @Test
    void returnsSharedValidationErrorWithoutRejectedValue() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .header("Authorization", "Bearer "
                                + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sensitive-account-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void hidesUnexpectedExceptionDetailsFromResponse() throws Exception {
        mockMvc.perform(get("/test/unexpected-error").header("Authorization", "Bearer "
                        + token(SIGNING_KEY, ISSUER, AUDIENCE, SCOPE, Instant.now().plusSeconds(60))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    private org.springframework.test.web.servlet.ResultActions requestWithToken(String token) throws Exception {
        return mockMvc.perform(get("/test/service-auth").header("Authorization", "Bearer " + token));
    }

    private static String token(KeyPair keyPair, String issuer, String audience, String scope, Instant expiresAt) {
        try {
            RSAKey signingJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("transaction-service")
                    .audience(audience)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .claim("scope", scope)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new RSASSASigner(signingJwk));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign test token", exception);
        }
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

    @org.springframework.web.bind.annotation.RestController
    static class ProbeController {
        @org.springframework.web.bind.annotation.GetMapping("/test/service-auth")
        String probe() {
            return "authorized";
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/trace-context")
        String traceContext() {
            return MDC.get("traceId");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/business-error")
        String businessError() {
            throw new BizException("DEMO_CONFLICT", "Demo request could not be completed");
        }

        @org.springframework.web.bind.annotation.PostMapping("/test/validation")
        String validation(@Valid @RequestBody ValidationRequest request) {
            return request.name();
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/unexpected-error")
        String unexpectedError() {
            throw new IllegalStateException("sensitive-token-value");
        }

        private record ValidationRequest(@Pattern(regexp = "approved") String name) {
        }
    }

    private static final class JwksServer implements AutoCloseable {
        private final HttpServer server;

        private JwksServer(KeyPair keyPair) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID(KEY_ID).build();
                byte[] body = new JWKSet(publicJwk).toString().getBytes(StandardCharsets.UTF_8);
                server.createContext("/jwks", exchange -> {
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

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
