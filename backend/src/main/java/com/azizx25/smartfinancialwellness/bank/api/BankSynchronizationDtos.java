package com.azizx25.smartfinancialwellness.bank.api;

import java.time.Instant;
import java.util.UUID;

public final class BankSynchronizationDtos {

    private BankSynchronizationDtos() {
    }

    public record SynchronizationSummary(
            UUID synchronizationId,
            String status,
            Instant startedAt,
            Instant completedAt,
            Instant lastSuccessfulAt,
            int accountsCreated,
            int accountsUpdated,
            int transactionsCreated,
            int transactionsUpdated) {
    }

    public record SynchronizationStatusResponse(
            String status,
            Instant lastAttemptedAt,
            Instant lastSuccessfulAt,
            String lastFailureCode,
            SynchronizationSummary latestSynchronization) {
    }
}
