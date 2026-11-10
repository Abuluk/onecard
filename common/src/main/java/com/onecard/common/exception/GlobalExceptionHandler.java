package com.onecard.common.exception;

import com.onecard.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import jakarta.validation.ConstraintViolationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BizException exception) {
        Result<Void> result = Result.failure(exception.getCode(), exception.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest().body(Result.failure(
                "VALIDATION_ERROR", "Request validation failed"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatusException(ResponseStatusException exception) {
        return switch (exception.getStatusCode().value()) {
            case 400 -> ResponseEntity.badRequest().body(Result.failure(
                    "REQUEST_INVALID", "Request is invalid"));
            case 401 -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.failure(
                    "AUTHENTICATION_REQUIRED", "Authentication is required"));
            case 403 -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.failure(
                    "ACCESS_DENIED", "Access is denied"));
            case 404 -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.failure(
                    "NOT_FOUND", "Resource was not found"));
            case 405 -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Result.failure(
                    "METHOD_NOT_ALLOWED", "Request method is not allowed"));
            case 409 -> ResponseEntity.status(HttpStatus.CONFLICT).body(Result.failure(
                    "CONFLICT", "Request conflicts with current state"));
            case 429 -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Result.failure(
                    "RATE_LIMITED", "Too many requests"));
            case 503 -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.failure(
                    "SERVICE_UNAVAILABLE", "A downstream service is unavailable"));
            case 504 -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Result.failure(
                    "DOWNSTREAM_TIMEOUT", "A downstream service timed out"));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.failure(
                    "INTERNAL_ERROR", "An unexpected error occurred"));
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        if (hasCause(exception, SocketTimeoutException.class, TimeoutException.class)) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Result.failure(
                    "DOWNSTREAM_TIMEOUT", "A downstream service timed out"));
        }
        if (hasCause(exception, ConnectException.class, UnknownHostException.class)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.failure(
                    "SERVICE_UNAVAILABLE", "A downstream service is unavailable"));
        }
        Throwable safeStack = new RuntimeException("Unhandled request exception");
        safeStack.setStackTrace(exception.getStackTrace());
        LOGGER.error("Unhandled request exception type={}", exception.getClass().getName(), safeStack);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.failure(
                "INTERNAL_ERROR", "An unexpected error occurred"));
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
}
