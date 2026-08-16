package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankReconnectionRequiredException extends ApiException {

    public BankReconnectionRequiredException() {
        super(HttpStatus.CONFLICT, "Mock-bank reconnection is required");
    }
}
