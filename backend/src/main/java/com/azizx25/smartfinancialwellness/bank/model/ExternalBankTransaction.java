package com.azizx25.smartfinancialwellness.bank.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExternalBankTransaction(
        String externalTransactionId,
        LocalDate bookingDate,
        LocalDate valueDate,
        BigDecimal amount,
        String direction,
        String currency,
        String description,
        String counterpartyName,
        String status) {
}
