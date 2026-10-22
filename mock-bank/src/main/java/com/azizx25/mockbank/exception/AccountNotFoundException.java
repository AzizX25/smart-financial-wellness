package com.azizx25.mockbank.exception;

public class AccountNotFoundException extends MockBankDomainException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}