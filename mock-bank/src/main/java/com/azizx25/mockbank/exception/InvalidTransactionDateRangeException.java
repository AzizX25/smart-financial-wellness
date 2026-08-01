package com.azizx25.mockbank.exception;

public class InvalidTransactionDateRangeException extends MockBankDomainException {

    public InvalidTransactionDateRangeException() {
        super("Transaction from-date must not be after to-date");
    }
}