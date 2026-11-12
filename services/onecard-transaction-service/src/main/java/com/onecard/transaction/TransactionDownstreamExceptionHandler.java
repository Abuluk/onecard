package com.onecard.transaction;

import com.onecard.common.result.Result;
import feign.FeignException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TransactionDownstreamExceptionHandler {

    @ExceptionHandler(NoFallbackAvailableException.class)
    ResponseEntity<Result<Void>> handleNoFallbackAvailable(NoFallbackAvailableException exception) {
        if (isTimeout(exception)) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Result.failure(
                    "DOWNSTREAM_TIMEOUT", "A downstream service timed out"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.failure(
                "SERVICE_UNAVAILABLE", "A downstream service is unavailable"));
    }

    private static boolean isTimeout(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof FeignException feignException && feignException.status() == 504) {
                return true;
            }
        }
        return false;
    }
}
