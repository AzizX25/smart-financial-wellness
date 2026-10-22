package com.azizx25.mockbank.exception;

public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String path) {
}
