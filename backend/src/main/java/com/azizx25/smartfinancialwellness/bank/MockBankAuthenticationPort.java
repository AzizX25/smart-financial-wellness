package com.azizx25.smartfinancialwellness.bank;

import java.time.Instant;

public interface MockBankAuthenticationPort {

    BankLoginResult login(String username, String password);

    record BankLoginResult(
            String customerId,
            String accessToken,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "BankLoginResult[customerId=<redacted>, accessToken=<redacted>, expiresAt="
                    + expiresAt + "]";
        }
    }
}
