package com.onecard.gateway;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final JsonMapper jsonMapper;

    GatewayExceptionHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }

        ErrorResponse error = errorResponse(exception);
        String traceId = traceId(exchange);
        if (error.status().is5xxServerError()) {
            LOGGER.error("request_failed service=onecard-gateway error_type={} trace_id={}",
                    exception.getClass().getName(), traceId);
        }
        exchange.getResponse().setStatusCode(error.status());
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body = jsonMapper.writeValueAsBytes(error.body());
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static ErrorResponse errorResponse(Throwable exception) {
        Integer statusCode = statusFromResponseStatus(exception);
        if (statusCode != null) {
            return switch (statusCode) {
                case 400 -> response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Request is invalid");
                case 401 -> response(HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "Authentication is required");
                case 403 -> response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied");
                case 404 -> response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource was not found");
                case 405 -> response(HttpStatus.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED", "Request method is not allowed");
                case 409 -> response(HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with current state");
                case 429 -> response(HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED", "Too many requests");
                case 503 -> response(HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE", "A downstream service is unavailable");
                case 504 -> response(HttpStatus.GATEWAY_TIMEOUT,
                        "DOWNSTREAM_TIMEOUT", "A downstream service timed out");
                default -> response(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR", "An unexpected error occurred");
            };
        }
        if (exception instanceof WebClientResponseException) {
            return response(HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", "A downstream service is unavailable");
        }
        if (hasCause(exception, SocketTimeoutException.class, TimeoutException.class)
                || hasTimeoutType(exception)) {
            return response(HttpStatus.GATEWAY_TIMEOUT,
                    "DOWNSTREAM_TIMEOUT", "A downstream service timed out");
        }
        if (hasCause(exception, ConnectException.class, UnknownHostException.class)) {
            return response(HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", "A downstream service is unavailable");
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static Integer statusFromResponseStatus(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof ResponseStatusException responseStatusException) {
                HttpStatusCode statusCode = responseStatusException.getStatusCode();
                return statusCode.value();
            }
        }
        return null;
    }

    @SafeVarargs
    private static boolean hasCause(Throwable exception, Class<? extends Throwable>... types) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTimeoutType(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current.getClass().getSimpleName().contains("TimeoutException")) {
                return true;
            }
        }
        return false;
    }

    static String traceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingContext>get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(Span::context)
                .map(traceContext -> traceContext.traceId())
                .orElse("unknown");
    }

    private static ErrorResponse response(HttpStatus status, String code, String message) {
        return new ErrorResponse(status, new ErrorBody(code, message, null));
    }

    private record ErrorResponse(HttpStatus status, ErrorBody body) {
    }

    private record ErrorBody(String code, String message, Object data) {
    }
}
