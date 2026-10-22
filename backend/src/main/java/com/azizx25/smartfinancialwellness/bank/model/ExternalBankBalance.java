package com.azizx25.smartfinancialwellness.bank.model;

import java.math.BigDecimal;

public record ExternalBankBalance(
        String externalAccountId,
        String currency,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        String status,
        String maskedAccountReference) {
}
