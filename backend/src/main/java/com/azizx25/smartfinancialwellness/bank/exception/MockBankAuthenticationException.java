package com.azizx25.smartfinancialwellness.bank.exception;

public class MockBankAuthenticationException extends RuntimeException {

    public MockBankAuthenticationException() {
        super("Mock-bank authentication is invalid or expired");
    }
}
