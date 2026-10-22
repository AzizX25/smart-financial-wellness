package com.azizx25.mockbank.exception;

public class CustomerNotFoundException extends MockBankDomainException {

    public CustomerNotFoundException() {
        super("Customer not found");
    }
}