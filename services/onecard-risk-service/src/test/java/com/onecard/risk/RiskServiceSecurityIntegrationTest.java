package com.onecard.risk;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RiskServiceSecurityIntegrationTest.ProbeController.class)
class RiskServiceSecurityIntegrationTest {

    private static final String ISSUER = "http://issuer.test/realms/onecard-dev";
    private static final String AUDIENCE = "onecard-risk-service";
    private static final String SCOPE = "onecard.risk.invoke";
    private static final String KEY_ID = "risk-service-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
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
    void requiresRiskServiceIdentityAndScope() throws Exception {
        mockMvc.perform(get("/test/service-auth")).andExpect(status().isUnauthorized());
        requestWithToken(token(AUDIENCE, "onecard.account.invoke", Instant.now().plusSeconds(60)))
                .andExpect(status().isForbidden());
        requestWithToken(token(AUDIENCE, SCOPE, Instant.now().plusSeconds(60))).andExpect(status().isOk());
    }

    @Test
    void rejectsTokensForAnotherAudience() throws Exception {
        requestWithToken(token("onecard-account-service", SCOPE, Instant.now().plusSeconds(60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leavesHealthPublicButProtectsOtherManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer "
                        + token(AUDIENCE, SCOPE, Instant.now().plusSeconds(60))))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions requestWithToken(String token) throws Exception {
        return mockMvc.perform(get("/test/service-auth").header("Authorization", "Bearer " + token));
    }

    private static String token(String audience, String scope, Instant expiresAt) {
        try {
            RSAKey signingJwk = new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic())
                    .privateKey(SIGNING_KEY.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
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
