package com.azizx25.mockbank.exception;

public class AccountNotOwnedException extends MockBankDomainException {

    public AccountNotOwnedException() {
        super("Account does not belong to the requested customer");
    }
}