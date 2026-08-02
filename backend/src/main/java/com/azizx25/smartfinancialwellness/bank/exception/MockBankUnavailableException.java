package com.azizx25.smartfinancialwellness.bank.exception;

public class MockBankUnavailableException extends RuntimeException {

    public MockBankUnavailableException() {
        super("Mock-bank service is unavailable");
    }
}
