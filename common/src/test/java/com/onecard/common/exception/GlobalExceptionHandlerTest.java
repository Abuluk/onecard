package com.onecard.common.exception;

import com.onecard.common.result.Result;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.springframework.core.MethodParameter;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void businessExceptionReturnsTheSharedErrorBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BizException("DEMO_NOT_FOUND", "Demo resource not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Result.failure("DEMO_NOT_FOUND", "Demo resource not found"));
    }

    @Test
    void validationFailureReturnsGenericClientErrorWithoutRejectedValue() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Method method = InvalidRequest.class.getDeclaredMethod("submit", String.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "accountId", "sensitive-account-value",
                false, null, null, "must be valid"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0), bindingResult);

        ResponseEntity<Result<Void>> response = handler.handleValidationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Result.failure("VALIDATION_ERROR", "Request validation failed"));
        assertThat(response.getBody().toString()).doesNotContain("sensitive-account-value");
    }

    @Test
    void unexpectedExceptionReturnsGenericServerErrorWithoutExceptionDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("sensitive-token-value"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(Result.failure("INTERNAL_ERROR", "An unexpected error occurred"));
        assertThat(response.getBody().toString()).doesNotContain("sensitive-token-value");
    }

    @Test
    void downstreamConnectionFailureReturnsUnavailableWithoutInternalAddress() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException(new ConnectException("internal-host secret-value")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(Result.failure(
                "SERVICE_UNAVAILABLE", "A downstream service is unavailable"));
        assertThat(response.getBody().toString()).doesNotContain("internal-host", "secret-value");
    }

    @Test
    void downstreamTimeoutReturnsGatewayTimeoutWithoutInternalDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException(new SocketTimeoutException("internal-host secret-value")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isEqualTo(Result.failure(
                "DOWNSTREAM_TIMEOUT", "A downstream service timed out"));
        assertThat(response.getBody().toString()).doesNotContain("internal-host", "secret-value");
    }

    @Test
    void responseStatusErrorsUseSharedCodeAndGenericMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "sensitive details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isEqualTo(Result.failure("RATE_LIMITED", "Too many requests"));
        assertThat(response.getBody().toString()).doesNotContain("sensitive details");
    }

    private static class InvalidRequest {
        @SuppressWarnings("unused")
        void submit(String accountId) {
        }
    }
}
