package com.azizx25.smartfinancialwellness.bank;

public record BankAccessCredentials(
        String customerId,
        String accessToken) {

    @Override
    public String toString() {
        return "BankAccessCredentials[customerId=<redacted>, accessToken=<redacted>]";
    }
}
