package com.azizx25.smartfinancialwellness.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.azizx25.smartfinancialwellness.category.DeterministicCategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azizx25.smartfinancialwellness.bank.BankConnectionService.AuthorizedBankConnection;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationStatusResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationSummary;
import com.azizx25.smartfinancialwellness.bank.exception.BankAuthorizationExpiredException;
import com.azizx25.smartfinancialwellness.bank.exception.BankSyncInProgressException;
import com.azizx25.smartfinancialwellness.bank.exception.BankSynchronizationFailedException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankAuthenticationException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankRejectedRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankUnavailableException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;
import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BankSynchronizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BankSynchronizationService.class);

    private static final int PROVIDER_PAGE_SIZE = 100;
    private static final int MAX_PROVIDER_PAGES = 10_000;
    private static final Set<String> ACCOUNT_TYPES =
            Set.of("CHECKING", "SAVINGS", "CREDIT", "CASH", "OTHER");
    private static final Set<String> DIRECTIONS = Set.of("DEBIT", "CREDIT");

    private final ReadOnlyBankDataPort bankDataPort;
    private final BankConnectionService connectionService;
    private final BankSynchronizationStore store;
    private final DeterministicCategorizationService categorizationService;

    public BankSynchronizationService(
            ReadOnlyBankDataPort bankDataPort,
            BankConnectionService connectionService,
            BankSynchronizationStore store,
            DeterministicCategorizationService categorizationService) {
        this.bankDataPort = bankDataPort;
        this.connectionService = connectionService;
        this.store = store;
        this.categorizationService = categorizationService;
    }

    public SynchronizationSummary synchronize(UUID userId) {
        AuthorizedBankConnection access = connectionService.requireAccess(userId, "ACCOUNTS_READ");
        UUID synchronizationId;
        try {
            synchronizationId = store.start(userId, access);
        } catch (DataIntegrityViolationException exception) {
            throw new BankSyncInProgressException();
        }

        try {
            BankSynchronizationBatch batch = retrieveAndValidate(access);
            SynchronizationSummary summary = store.complete(userId, synchronizationId, batch);
            try {
                categorizationService.categorizeUncategorized(userId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Deterministic categorization failed after synchronization {}", synchronizationId);
            }
            return summary;
        } catch (MockBankAuthenticationException exception) {
            connectionService.invalidateProviderAuthentication(userId, access.connectionId());
            store.fail(userId, synchronizationId, "BANK_AUTHORIZATION_EXPIRED");
            throw new BankAuthorizationExpiredException();
        } catch (MockBankUnavailableException exception) {
            store.fail(userId, synchronizationId, "BANK_PROVIDER_UNAVAILABLE");
            throw exception;
        } catch (MockBankNotFoundException
                | MockBankRejectedRequestException
                | MockBankProtocolException exception) {
            store.fail(userId, synchronizationId, "BANK_SYNC_FAILED");
            throw new BankSynchronizationFailedException();
        } catch (ApiException exception) {
            store.fail(userId, synchronizationId, "BANK_SYNC_FAILED");
            throw exception;
        } catch (RuntimeException exception) {
            store.fail(userId, synchronizationId, "BANK_SYNC_FAILED");
            throw new BankSynchronizationFailedException();
        }
    }

    public SynchronizationStatusResponse getStatus(UUID userId) {
        return store.status(userId);
    }

    private BankSynchronizationBatch retrieveAndValidate(AuthorizedBankConnection access) {
        List<ExternalBankAccount> accounts = bankDataPort.listAccounts(access.credentials());
        if (accounts == null) {
            throw new MockBankProtocolException();
        }

        Set<String> accountIds = new HashSet<>();
        List<BankSynchronizationBatch.AccountSnapshot> snapshots = new ArrayList<>();
        for (ExternalBankAccount account : accounts) {
            validateAccount(account);
            if (!accountIds.add(account.externalAccountId())) {
                throw new MockBankProtocolException();
            }

            ExternalBankBalance balance = bankDataPort.getBalance(
                    access.credentials(), account.externalAccountId());
            validateBalance(account, balance);
            List<ExternalBankTransaction> transactions = retrieveAllTransactions(
                    access, account);
            snapshots.add(new BankSynchronizationBatch.AccountSnapshot(
                    account, balance, transactions));
        }
        return new BankSynchronizationBatch(snapshots);
    }

    private List<ExternalBankTransaction> retrieveAllTransactions(
            AuthorizedBankConnection access,
            ExternalBankAccount account) {
        List<ExternalBankTransaction> transactions = new ArrayList<>();
        Set<String> transactionIds = new HashSet<>();
        int page = 0;
        int expectedTotalPages = -1;
        long expectedTotalElements = -1;

        while (page < MAX_PROVIDER_PAGES) {
            ExternalBankTransactionPage response = bankDataPort.listTransactions(
                    access.credentials(),
                    account.externalAccountId(),
                    null,
                    null,
                    page,
                    PROVIDER_PAGE_SIZE);
            validatePage(response, page);
            if (expectedTotalPages < 0) {
                expectedTotalPages = response.totalPages();
                expectedTotalElements = response.totalElements();
            } else if (expectedTotalPages != response.totalPages()
                    || expectedTotalElements != response.totalElements()) {
                throw new MockBankProtocolException();
            }

            for (ExternalBankTransaction transaction : response.items()) {
                validateTransaction(account, transaction);
                if (!transactionIds.add(transaction.externalTransactionId())) {
                    throw new MockBankProtocolException();
                }
                transactions.add(transaction);
            }

            if (response.last()) {
                if (transactions.size() != response.totalElements()) {
                    throw new MockBankProtocolException();
                }
                return List.copyOf(transactions);
            }
            if (response.totalPages() <= page + 1) {
                throw new MockBankProtocolException();
            }
            page++;
        }
        throw new MockBankProtocolException();
    }

    private void validateAccount(ExternalBankAccount account) {
        if (account == null
                || !hasText(account.externalAccountId(), 255)
                || !hasText(account.displayName(), 160)
                || !ACCOUNT_TYPES.contains(account.accountType())
                || !validCurrency(account.currency())
                || !hasText(account.maskedAccountReference(), 80)
                || !hasText(account.status(), 40)
                || account.createdAt() == null) {
            throw new MockBankProtocolException();
        }
    }

    private void validateBalance(ExternalBankAccount account, ExternalBankBalance balance) {
        if (balance == null
                || !account.externalAccountId().equals(balance.externalAccountId())
                || !account.currency().equals(balance.currency())
                || !hasText(balance.status(), 40)
                || !hasText(balance.maskedAccountReference(), 80)) {
            throw new MockBankProtocolException();
        }
        validateMoney(balance.currentBalance(), false);
        if (balance.availableBalance() != null) {
            validateMoney(balance.availableBalance(), false);
        }
    }

    private void validateTransaction(
            ExternalBankAccount account,
            ExternalBankTransaction transaction) {
        if (transaction == null
                || !hasText(transaction.externalTransactionId(), 255)
                || transaction.bookingDate() == null
                || !DIRECTIONS.contains(transaction.direction())
                || !account.currency().equals(transaction.currency())
                || !hasText(transaction.description(), 1000)
                || !hasText(transaction.status(), 40)
                || (transaction.counterpartyName() != null
                    && transaction.counterpartyName().length() > 255)) {
            throw new MockBankProtocolException();
        }
        validateMoney(transaction.amount(), true);
    }

    private void validatePage(ExternalBankTransactionPage response, int requestedPage) {
        if (response == null
                || response.items() == null
                || response.page() != requestedPage
                || response.size() < 1
                || response.size() > PROVIDER_PAGE_SIZE
                || response.items().size() > response.size()
                || response.totalElements() < 0
                || response.totalPages() < 0
                || response.totalPages() > MAX_PROVIDER_PAGES) {
            throw new MockBankProtocolException();
        }
    }

    private void validateMoney(BigDecimal value, boolean strictlyPositive) {
        if (value == null || (strictlyPositive && value.signum() <= 0)) {
            throw new MockBankProtocolException();
        }
        try {
            BigDecimal scaled = value.setScale(4, RoundingMode.UNNECESSARY);
            if (scaled.precision() > 19) {
                throw new MockBankProtocolException();
            }
        } catch (ArithmeticException exception) {
            throw new MockBankProtocolException();
        }
    }

    private boolean validCurrency(String currency) {
        return currency != null && currency.matches("[A-Z]{3}");
    }

    private boolean hasText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }
}
