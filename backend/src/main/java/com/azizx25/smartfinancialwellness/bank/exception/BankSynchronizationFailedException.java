package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankSynchronizationFailedException extends ApiException {

    public BankSynchronizationFailedException() {
        super(HttpStatus.BAD_GATEWAY, "BANK_SYNC_FAILED");
    }
}
