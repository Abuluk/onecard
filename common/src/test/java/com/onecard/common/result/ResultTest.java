package com.onecard.common.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void successContainsTheValueAndSuccessCode() {
        Result<String> result = Result.success("ready");

        assertThat(result.code()).isEqualTo("SUCCESS");
        assertThat(result.message()).isEqualTo("success");
        assertThat(result.data()).isEqualTo("ready");
    }

    @Test
    void failureContainsTheProvidedCodeAndMessage() {
        Result<Void> result = Result.failure("NOT_FOUND", "Resource not found");

        assertThat(result.code()).isEqualTo("NOT_FOUND");
        assertThat(result.message()).isEqualTo("Resource not found");
        assertThat(result.data()).isNull();
    }
}
