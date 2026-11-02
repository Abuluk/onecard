package com.onecard.common.exception;

import com.onecard.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
