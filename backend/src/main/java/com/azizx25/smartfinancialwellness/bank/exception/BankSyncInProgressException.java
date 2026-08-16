package com.azizx25.smartfinancialwellness.bank.exception;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class BankSyncInProgressException extends ApiException {

    public BankSyncInProgressException() {
        super(HttpStatus.CONFLICT, "BANK_SYNC_IN_PROGRESS");
    }
}
