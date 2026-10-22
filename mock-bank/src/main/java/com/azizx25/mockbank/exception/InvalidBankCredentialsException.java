package com.azizx25.mockbank.exception;

public class InvalidBankCredentialsException extends MockBankDomainException {

    public InvalidBankCredentialsException() {
        super("Invalid bank credentials");
    }
}
