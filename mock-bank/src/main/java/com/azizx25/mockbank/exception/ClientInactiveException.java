package com.azizx25.mockbank.exception;

public class ClientInactiveException extends MockBankDomainException {

    public ClientInactiveException() {
        super("OAuth client is inactive");
    }
}