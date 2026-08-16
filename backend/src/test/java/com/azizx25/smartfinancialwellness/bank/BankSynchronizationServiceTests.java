package com.azizx25.smartfinancialwellness.bank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.BankConnectionService.AuthorizedBankConnection;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationSummary;
import com.azizx25.smartfinancialwellness.bank.exception.BankSynchronizationFailedException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;
import com.azizx25.smartfinancialwellness.category.DeterministicCategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankSynchronizationServiceTests {

    @Mock
    private ReadOnlyBankDataPort bankDataPort;

    @Mock
    private BankConnectionService connectionService;

    @Mock
    private BankSynchronizationStore store;

    @Mock
    private DeterministicCategorizationService categorizationService;

    private BankSynchronizationService service;

    @BeforeEach
    void setUp() {
        service = new BankSynchronizationService(
                bankDataPort, connectionService, store, categorizationService);
    }

    @Test
    void retrievesEveryProviderPageBeforeOpeningPersistenceBoundary() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        AuthorizedBankConnection access = access();
        ExternalBankAccount account = account();
        ExternalBankTransaction first = transaction("TX-1", new BigDecimal("10.0000"));
        ExternalBankTransaction second = transaction("TX-2", new BigDecimal("20.0000"));
        SynchronizationSummary expected = new SynchronizationSummary(
                jobId, "SUCCEEDED", Instant.now(), Instant.now(), Instant.now(), 1, 0, 2, 0);

        when(connectionService.requireAccess(userId, "ACCOUNTS_READ")).thenReturn(access);
        when(store.start(userId, access)).thenReturn(jobId);
        when(bankDataPort.listAccounts(access.credentials())).thenReturn(List.of(account));
        when(bankDataPort.getBalance(access.credentials(), account.externalAccountId()))
                .thenReturn(balance());
        when(bankDataPort.listTransactions(
                access.credentials(), account.externalAccountId(), null, null, 0, 100))
                .thenReturn(new ExternalBankTransactionPage(
                        List.of(first), 0, 100, 2, 2, true, false));
        when(bankDataPort.listTransactions(
                access.credentials(), account.externalAccountId(), null, null, 1, 100))
                .thenReturn(new ExternalBankTransactionPage(
                        List.of(second), 1, 100, 2, 2, false, true));
        when(store.complete(eq(userId), eq(jobId), any())).thenReturn(expected);

        assertThat(service.synchronize(userId)).isSameAs(expected);

        ArgumentCaptor<BankSynchronizationBatch> batch =
                ArgumentCaptor.forClass(BankSynchronizationBatch.class);
        verify(store).complete(eq(userId), eq(jobId), batch.capture());
        assertThat(batch.getValue().accounts()).hasSize(1);
        assertThat(batch.getValue().transactionCount()).isEqualTo(2);
    }

    @Test
    void invalidProviderMoneyFailsSafelyBeforeAnyPersistenceUpsert() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        AuthorizedBankConnection access = access();
        ExternalBankAccount account = account();

        when(connectionService.requireAccess(userId, "ACCOUNTS_READ")).thenReturn(access);
        when(store.start(userId, access)).thenReturn(jobId);
        when(bankDataPort.listAccounts(access.credentials())).thenReturn(List.of(account));
        when(bankDataPort.getBalance(access.credentials(), account.externalAccountId()))
                .thenReturn(balance());
        when(bankDataPort.listTransactions(
                access.credentials(), account.externalAccountId(), null, null, 0, 100))
                .thenReturn(new ExternalBankTransactionPage(
                        List.of(transaction("TX-INVALID", BigDecimal.ZERO)),
                        0, 100, 1, 1, true, true));

        assertThatThrownBy(() -> service.synchronize(userId))
                .isInstanceOf(BankSynchronizationFailedException.class);
        verify(store).fail(userId, jobId, "BANK_SYNC_FAILED");
        verify(store, never()).complete(eq(userId), eq(jobId), any());
    }

    private AuthorizedBankConnection access() {
        return new AuthorizedBankConnection(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BankAccessCredentials("CUSTOMER-1", "redacted-test-token"));
    }

    private ExternalBankAccount account() {
        return new ExternalBankAccount(
                "ACCOUNT-1",
                "Compte test",
                "CHECKING",
                "TND",
                "****0001",
                "ACTIVE",
                OffsetDateTime.of(2026, 8, 4, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private ExternalBankBalance balance() {
        return new ExternalBankBalance(
                "ACCOUNT-1",
                "TND",
                new BigDecimal("100.0000"),
                new BigDecimal("90.0000"),
                "ACTIVE",
                "****0001");
    }

    private ExternalBankTransaction transaction(String id, BigDecimal amount) {
        return new ExternalBankTransaction(
                id,
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 4),
                amount,
                "DEBIT",
                "TND",
                "Opération test",
                "Contrepartie test",
                "POSTED");
    }
}
