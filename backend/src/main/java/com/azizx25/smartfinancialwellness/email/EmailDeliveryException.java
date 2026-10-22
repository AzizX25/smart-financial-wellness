package com.azizx25.smartfinancialwellness.email;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class EmailDeliveryException extends ApiException {

    public EmailDeliveryException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is temporarily unavailable");
    }
}
