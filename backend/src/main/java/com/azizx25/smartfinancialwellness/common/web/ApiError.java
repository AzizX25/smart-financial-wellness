package com.azizx25.smartfinancialwellness.common.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, Map.of());
    }

    public static ApiError validation(String message, String path, Map<String, String> fieldErrors) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }
}
