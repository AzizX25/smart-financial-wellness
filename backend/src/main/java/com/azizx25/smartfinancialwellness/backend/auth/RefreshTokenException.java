package com.azizx25.smartfinancialwellness.backend.auth;

import com.azizx25.smartfinancialwellness.backend.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class RefreshTokenException extends ApiException {

    public RefreshTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
