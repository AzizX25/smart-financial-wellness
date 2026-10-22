package com.azizx25.smartfinancialwellness.bank.exception;

public class MockBankProtocolException extends RuntimeException {

    public MockBankProtocolException() {
        super("Mock-bank response violated the expected contract");
    }
}
