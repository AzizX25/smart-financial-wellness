package com.azizx25.smartfinancialwellness.transaction;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class ManualTransactionException extends ApiException {
    private ManualTransactionException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ManualTransactionException notFound() {
        return new ManualTransactionException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Opération ou compte introuvable.");
    }

    public static ManualTransactionException invalid(String message) {
        return new ManualTransactionException(HttpStatus.BAD_REQUEST, "INVALID_MANUAL_TRANSACTION", message);
    }

    public static ManualTransactionException conflict(String message) {
        return new ManualTransactionException(HttpStatus.CONFLICT, "MANUAL_TRANSACTION_CONFLICT", message);
    }
}
