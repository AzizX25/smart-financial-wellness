package com.azizx25.mockbank.exception;

public class InvalidTransactionPaginationException extends MockBankDomainException {

    public InvalidTransactionPaginationException(String message) {
        super(message);
    }
}
