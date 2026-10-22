package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankAccountNotFoundException extends ApiException {

    public BankAccountNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Bank account not found");
    }
}
