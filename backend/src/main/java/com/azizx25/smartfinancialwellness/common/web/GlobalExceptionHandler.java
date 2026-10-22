package com.azizx25.smartfinancialwellness.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.azizx25.smartfinancialwellness.auth.RefreshCookieService;
import com.azizx25.smartfinancialwellness.auth.RefreshTokenException;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidBankRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidMockBankCredentialsException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankAuthenticationException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankRejectedRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
                .body(ApiError.of(
                        exception.getStatus(),
                        exception.getCode(),
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(InvalidMockBankCredentialsException.class)
    ResponseEntity<ApiError> handleInvalidBankCredentials(
            InvalidMockBankCredentialsException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(MockBankAuthenticationException.class)
    ResponseEntity<ApiError> handleMockBankAuthentication(
            MockBankAuthenticationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT,
                        "Mock-bank reconnection is required",
                        request.getRequestURI()));
    }

    @ExceptionHandler(MockBankNotFoundException.class)
    ResponseEntity<ApiError> handleMockBankNotFound(
            MockBankNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler({InvalidBankRequestException.class, MockBankRejectedRequestException.class})
    ResponseEntity<ApiError> handleInvalidBankRequest(
            RuntimeException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MockBankUnavailableException.class)
    ResponseEntity<ApiError> handleMockBankUnavailable(
            MockBankUnavailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        exception.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(MockBankProtocolException.class)
    ResponseEntity<ApiError> handleMockBankProtocol(
            MockBankProtocolException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(
                        HttpStatus.BAD_GATEWAY,
                        exception.getMessage(),
                        request.getRequestURI()));
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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST,
                        "Invalid value for request parameter '" + exception.getName() + "'",
                        request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        String[] supported = exception.getSupportedMethods();
        if (supported != null) response.header(HttpHeaders.ALLOW, String.join(", ", supported));
        return response.body(ApiError.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Cette action n’est pas disponible pour cette ressource.", request.getRequestURI()));
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
