package com.azizx25.mockbank.exception;

public class InvalidRedirectUriException extends MockBankDomainException {

    public InvalidRedirectUriException() {
        super("Redirect URI is not registered for this client");
    }
}