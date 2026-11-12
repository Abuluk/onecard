package com.onecard.gateway;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class UntrustedIdentityHeaderFilter implements GlobalFilter, Ordered {

    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id",
            "X-User-Roles",
            "X-Authenticated-User",
            "X-Authenticated-Roles",
            "X-Auth-Request-User",
            "X-Auth-Request-Roles",
            "X-Forwarded-User",
            "X-Remote-User",
            "X-Remote-Groups",
            "X-User-Email",
            "X-User-Groups",
            "X-Authenticated-Subject",
            "X-Principal",
            "X-Roles");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
