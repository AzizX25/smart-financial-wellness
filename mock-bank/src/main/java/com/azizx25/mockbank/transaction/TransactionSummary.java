package com.azizx25.mockbank.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionSummary(
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
