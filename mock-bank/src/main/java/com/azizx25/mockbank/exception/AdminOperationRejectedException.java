package com.azizx25.mockbank.exception;

public class AdminOperationRejectedException extends MockBankDomainException {

    public AdminOperationRejectedException(String message) {
        super(message);
    }
}
