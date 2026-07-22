package com.azizx25.smartfinancialwellness.backend.auth;

public class RefreshTokenReuseException extends RefreshTokenException {

    public RefreshTokenReuseException() {
        super("Refresh token reuse was detected");
    }
}
