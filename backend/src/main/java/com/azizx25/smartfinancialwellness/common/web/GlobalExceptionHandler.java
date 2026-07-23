package com.azizx25.smartfinancialwellness.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.azizx25.smartfinancialwellness.auth.RefreshCookieService;
import com.azizx25.smartfinancialwellness.auth.RefreshTokenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final RefreshCookieService refreshCookieService;

    public GlobalExceptionHandler(RefreshCookieService refreshCookieService) {
        this.refreshCookieService = refreshCookieService;
    }

    @ExceptionHandler(RefreshTokenException.class)
    ResponseEntity<ApiError> handleRefreshTokenException(
            RefreshTokenException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString())
                .body(ApiError.of(exception.getStatus(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiError.of(exception.getStatus(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.validation("Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.validation("Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "Request body is invalid", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred",
                        request.getRequestURI()));
    }
}
