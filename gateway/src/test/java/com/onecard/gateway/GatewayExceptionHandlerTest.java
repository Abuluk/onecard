package com.onecard.gateway;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler(JsonMapper.builder().build());

    @Test
    void mapsDownstreamConnectionFailureToGenericUnavailableResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account/balance").build());

        handler.handle(exchange, new IllegalStateException(
                new ConnectException("internal-host secret-token"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("SERVICE_UNAVAILABLE", "A downstream service is unavailable")
                .doesNotContain("internal-host", "secret-token");
    }

    @Test
    void mapsDownstreamTimeoutToGatewayTimeout() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account/balance").build());

        handler.handle(exchange, new IllegalStateException(
                new SocketTimeoutException("internal-host secret-token"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("DOWNSTREAM_TIMEOUT", "A downstream service timed out")
                .doesNotContain("internal-host", "secret-token");
    }

    @Test
    void mapsRateLimitResponseStatusToSharedErrorBody() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account/balance").build());

        handler.handle(exchange,
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "sensitive details")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("RATE_LIMITED", "Too many requests")
                .doesNotContain("sensitive details");
    }
}
