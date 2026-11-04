package com.azizx25.mockbank;

import java.security.SecureRandom;
import java.util.Base64;

/** Exists only in tests. No credential is taken from production migrations. */
final class TestBankCredentials {
    static final String ADMIN_USERNAME = "integration.admin";
    static final String ADMIN_PASSWORD = randomSecret();
    static final String CUSTOMER_PASSWORD = randomSecret();

    private TestBankCredentials() {
    }

    static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
