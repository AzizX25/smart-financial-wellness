package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankAuthorizationExpiredException extends ApiException {

    public BankAuthorizationExpiredException() {
        super(HttpStatus.CONFLICT, "BANK_AUTHORIZATION_EXPIRED");
    }
}
