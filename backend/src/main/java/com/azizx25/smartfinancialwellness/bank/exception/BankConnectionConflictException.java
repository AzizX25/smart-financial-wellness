package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankConnectionConflictException extends ApiException {

    public BankConnectionConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
