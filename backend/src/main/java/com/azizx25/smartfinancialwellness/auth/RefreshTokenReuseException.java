package com.azizx25.smartfinancialwellness.auth;

public class RefreshTokenReuseException extends RefreshTokenException {

    public RefreshTokenReuseException() {
        super("Refresh token reuse was detected");
    }
}
