package com.azizx25.mockbank.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class MockBankExceptionHandler {

    @ExceptionHandler({
            CustomerNotFoundException.class,
            AccountNotFoundException.class,
            AccountNotOwnedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            MockBankDomainException exception,
            HttpServletRequest request) {

        return createNotFoundResponse(exception.getMessage(), request);
    }

    @ExceptionHandler({
            InvalidTransactionDateRangeException.class,
            InvalidTransactionPaginationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            MockBankDomainException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        String message = "Invalid value for query parameter '" + exception.getName() + "'";
        return createErrorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    private ResponseEntity<ApiErrorResponse> createNotFoundResponse(
            String message,
            HttpServletRequest request) {

        return createErrorResponse(HttpStatus.NOT_FOUND, message, request);
    }

    private ResponseEntity<ApiErrorResponse> createErrorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        ApiErrorResponse response = new ApiErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());

        return ResponseEntity.status(status).body(response);
    }
}
