package com.azizx25.mockbank.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private ResponseEntity<ApiErrorResponse> createNotFoundResponse(
            String message,
            HttpServletRequest request) {

        HttpStatus status = HttpStatus.NOT_FOUND;

        ApiErrorResponse response = new ApiErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());

        return ResponseEntity.status(status).body(response);
    }
}
