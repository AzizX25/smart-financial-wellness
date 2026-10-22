package com.azizx25.mockbank.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class MockBankExceptionHandler {

    @ExceptionHandler(InvalidBankCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidBankCredentialsException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Request validation failed", request);
    }

    @ExceptionHandler({
            CustomerNotFoundException.class,
            AccountNotFoundException.class,
            AccountNotOwnedException.class,
            AdminResourceNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            MockBankDomainException exception,
            HttpServletRequest request) {

        return createNotFoundResponse(exception.getMessage(), request);
    }

    @ExceptionHandler({
            InvalidTransactionDateRangeException.class,
            InvalidTransactionPaginationException.class,
            InvalidAdminRequestException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            MockBankDomainException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(AdminConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            AdminConflictException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(AdminOperationRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleRejectedOperation(
            AdminOperationRejectedException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, "Malformed JSON request", request);
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
