package com.azizx25.mockbank.account;

import java.math.BigDecimal;

public record BalanceSummary(
        String externalAccountId,
        String currency,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        String status,
        String maskedAccountReference) {
}