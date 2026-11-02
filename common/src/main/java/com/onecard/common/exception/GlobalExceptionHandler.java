package com.onecard.common.exception;

import com.onecard.common.result.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BizException exception) {
        Result<Void> result = Result.failure(exception.getCode(), exception.getMessage());
        return ResponseEntity.badRequest().body(result);
    }
}
