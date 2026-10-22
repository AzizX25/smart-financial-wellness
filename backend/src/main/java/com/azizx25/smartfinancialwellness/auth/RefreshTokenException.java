package com.azizx25.smartfinancialwellness.auth;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class RefreshTokenException extends ApiException {

    public RefreshTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
