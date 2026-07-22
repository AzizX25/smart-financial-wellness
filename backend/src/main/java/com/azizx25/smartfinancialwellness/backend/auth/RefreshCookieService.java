package com.azizx25.smartfinancialwellness.backend.auth;

import java.time.Duration;

import com.azizx25.smartfinancialwellness.backend.security.SecurityProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class RefreshCookieService {

    public static final String COOKIE_NAME = "sfwa_refresh";
    public static final String COOKIE_PATH = "/api/auth";

    private final SecurityProperties properties;

    public RefreshCookieService(SecurityProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie create(String rawToken) {
        return base(rawToken)
                .maxAge(properties.refreshTokenTtl())
                .build();
    }

    public ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }
}
