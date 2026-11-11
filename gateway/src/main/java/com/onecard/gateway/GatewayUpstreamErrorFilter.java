package com.onecard.gateway;

import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class GatewayUpstreamErrorFilter implements GlobalFilter, Ordered {

    private final JsonMapper jsonMapper;

    GatewayUpstreamErrorFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return writeOrSanitize(body, getStatusCode());
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeOrSanitize(Flux.from(body).concatMap(Flux::from), getStatusCode());
            }

            private Mono<Void> writeOrSanitize(Publisher<? extends DataBuffer> body, HttpStatusCode status) {
                if (status == null || !status.is5xxServerError()) {
                    return super.writeWith(body);
                }

                ErrorBody error = errorBody(status);
                byte[] bytes = jsonMapper.writeValueAsBytes(error);
                HttpHeaders headers = getHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setCacheControl("no-store");
                headers.setContentLength(bytes.length);
                headers.remove(HttpHeaders.CONTENT_ENCODING);
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.remove(HttpHeaders.ETAG);
                headers.remove("Content-MD5");

                return Flux.from(body)
                        .doOnNext(DataBufferUtils::release)
                        .onErrorResume(ignored -> Flux.empty())
                        .then(Mono.defer(() -> super.writeWith(
                                Mono.just(bufferFactory().wrap(bytes)))));
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    private static ErrorBody errorBody(HttpStatusCode status) {
        return switch (status.value()) {
            case 503 -> new ErrorBody("SERVICE_UNAVAILABLE", "A downstream service is unavailable", null);
            case 504 -> new ErrorBody("DOWNSTREAM_TIMEOUT", "A downstream service timed out", null);
            default -> new ErrorBody("INTERNAL_ERROR", "An unexpected error occurred", null);
        };
    }

    private record ErrorBody(String code, String message, Object data) {
    }
}
