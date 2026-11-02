package com.onecard.common.api;

public record Result<T>(String code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>("SUCCESS", "success", data);
    }

    public static <T> Result<T> failure(String code, String message) {
        return new Result<>(code, message, null);
    }
}
