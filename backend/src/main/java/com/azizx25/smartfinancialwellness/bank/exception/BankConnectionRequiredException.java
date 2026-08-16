package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankConnectionRequiredException extends ApiException {

    public BankConnectionRequiredException() {
        super(HttpStatus.CONFLICT, "A mock-bank connection is required");
    }
}
