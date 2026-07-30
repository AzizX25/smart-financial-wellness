package com.azizx25.smartfinancialwellness.auth;

import java.util.Locale;

final class EmailNormalizer {

    private EmailNormalizer() {
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
