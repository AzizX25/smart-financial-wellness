package com.azizx25.mockbank.exception;

public class ClientNotFoundException extends MockBankDomainException {

    public ClientNotFoundException() {
        super("OAuth client not found");
    }
}