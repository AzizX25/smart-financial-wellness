package com.azizx25.smartfinancialwellness.bank.exception;

public class MockBankRejectedRequestException extends RuntimeException {

    public MockBankRejectedRequestException() {
        super("Mock-bank rejected the request");
    }
}
