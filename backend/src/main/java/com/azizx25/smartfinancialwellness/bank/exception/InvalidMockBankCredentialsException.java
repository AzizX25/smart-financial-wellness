package com.azizx25.smartfinancialwellness.bank.exception;

public class InvalidMockBankCredentialsException extends RuntimeException {

    public InvalidMockBankCredentialsException() {
        super("Invalid mock-bank credentials");
    }
}
