package com.azizx25.mockbank.exception;

public abstract class MockBankDomainException extends RuntimeException {

    protected MockBankDomainException(String message) {
        super(message);
    }
}