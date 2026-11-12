package com.onecard.gateway;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewaySubjectRateLimitFilterTest {

    private static final long WINDOW_MILLIS = 60_000L;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private GatewayFilterChain chain;

    private DefaultRedisScript<Long> script;
    private GatewaySubjectRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        script = new DefaultRedisScript<>("return 1", Long.class);
        filter = new GatewaySubjectRateLimitFilter(redisTemplate, script, JsonMapper.builder().build());
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void readRequestsUseTheSixtyPerMinuteSubjectBucket() {
        when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenReturn(Flux.just(1L));

        exchange(HttpMethod.GET, "user-123");

        assertPolicy("read", "user-123", 60);
        verify(chain).filter(any());
    }

    @Test
    void writeRequestsUseTheTenPerMinuteSubjectBucket() {
        when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenReturn(Flux.just(1L));

        exchange(HttpMethod.POST, "user-123");

        assertPolicy("write", "user-123", 10);
        verify(chain).filter(any());
    }

    @Test
    void rejectsAnExhaustedBucketWithTheStandard429Body() {
        when(redisTemplate.execute(eq(script), anyList(), any(Object[].class))).thenReturn(Flux.just(0L));
        var exchange = exchange(HttpMethod.GET, "user-123");

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("RATE_LIMITED", "Too many requests")
                .doesNotContain("user-123");
        verify(chain, never()).filter(any());
    }

    @Test
    void failsClosedWithAStandard503WhenValkeyIsUnavailable() {
        when(redisTemplate.execute(eq(script), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new IllegalStateException("valkey secret details")));

        var exchange = exchange(HttpMethod.GET, "user-123");

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("SERVICE_UNAVAILABLE", "A downstream service is unavailable")
                .doesNotContain("valkey", "secret details");
        verify(chain, never()).filter(any());
    }

    @Test
    void rejectsRequestsWithoutAJwtSubjectBeforeCallingValkey() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account/balance").build());
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
    }

    private MockServerWebExchange exchange(HttpMethod method, String subject) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, "/api/account/balance").build());
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        filter.filter(exchange, chain)
                .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                        .withAuthentication(new JwtAuthenticationToken(jwt, List.of())))
                .block();
        return exchange;
    }

    private void assertPolicy(String operation, String subject, int limit) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(eq(script), keys.capture(), arguments.capture());

        String hash = subjectHash(subject);
        assertThat(keys.getValue()).containsExactly("onecard:gateway:rate-limit:v1:" + operation + ":" + hash);
        assertThat(arguments.getValue()).containsExactly(Long.toString(WINDOW_MILLIS), Integer.toString(limit),
                arguments.getValue()[2]);
        assertThat(arguments.getValue()[2].toString()).isNotBlank();
    }

    private static String subjectHash(String subject) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
