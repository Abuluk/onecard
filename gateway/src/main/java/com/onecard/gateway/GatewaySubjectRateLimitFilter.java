package com.onecard.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "onecard.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
final class GatewaySubjectRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewaySubjectRateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int READ_LIMIT = 60;
    private static final int WRITE_LIMIT = 10;
    private static final String KEY_PREFIX = "onecard:gateway:rate-limit:v1:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final JsonMapper jsonMapper;

    GatewaySubjectRateLimitFilter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<Long> rateLimitScript,
            JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String operation;
        int limit;
        if (HttpMethod.GET.equals(method)) {
            operation = "read";
            limit = READ_LIMIT;
        } else if (HttpMethod.POST.equals(method)) {
            operation = "write";
            limit = WRITE_LIMIT;
        } else {
            return chain.filter(exchange);
        }

        Mono<Decision> decision = ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(token -> token.getSubject())
                .filter(subject -> subject != null && !subject.isBlank())
                .flatMap(subject -> consume(operation, subject, limit))
                .defaultIfEmpty(Decision.UNAUTHENTICATED);

        return decision.flatMap(result -> switch (result) {
            case ALLOWED -> chain.filter(exchange);
            case DENIED -> writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED", "Too many requests");
            case UNAVAILABLE -> writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", "A downstream service is unavailable");
            case UNAUTHENTICATED -> writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication is required");
        });
    }

    private Mono<Decision> consume(String operation, String subject, int limit) {
        String key = KEY_PREFIX + operation + ":" + subjectHash(subject);
        return redisTemplate.execute(rateLimitScript, List.of(key),
                        Long.toString(WINDOW_MILLIS), Integer.toString(limit),
                        UUID.randomUUID().toString())
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("Valkey returned no rate-limit result")))
                .map(allowed -> allowed == 1L ? Decision.ALLOWED : Decision.DENIED)
                .onErrorResume(exception -> {
                    LOGGER.error("rate_limit_store_unavailable service=onecard-gateway error_type={}",
                            exception.getClass().getName());
                    return Mono.just(Decision.UNAVAILABLE);
                });
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        byte[] body = jsonMapper.writeValueAsBytes(new ErrorBody(code, message, null));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static String subjectHash(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum Decision {
        ALLOWED,
        DENIED,
        UNAVAILABLE,
        UNAUTHENTICATED
    }

    private record ErrorBody(String code, String message, Object data) {
    }
}
