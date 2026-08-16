package com.azizx25.smartfinancialwellness.bank;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.BankConnectionService.AuthorizedBankConnection;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationStatusResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationSummary;
import com.azizx25.smartfinancialwellness.bank.exception.BankConnectionRequiredException;
import com.azizx25.smartfinancialwellness.bank.exception.BankReconnectionRequiredException;
import com.azizx25.smartfinancialwellness.bank.exception.BankSyncInProgressException;
import com.azizx25.smartfinancialwellness.bank.exception.BankSynchronizationFailedException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnection;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorization;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorizationRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.SyncJob;
import com.azizx25.smartfinancialwellness.bank.persistence.SyncJobRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class BankSynchronizationStore {

    private static final EnumSet<SyncJob.Status> ACTIVE_JOB_STATUSES =
            EnumSet.of(SyncJob.Status.QUEUED, SyncJob.Status.RUNNING);

    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAuthorizationRepository authorizationRepository;
    private final FinancialAccountRepository accountRepository;
    private final SyncJobRepository syncJobRepository;
    private final TransactionRecordRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    BankSynchronizationStore(
            BankConnectionRepository connectionRepository,
            BankConnectionAuthorizationRepository authorizationRepository,
            FinancialAccountRepository accountRepository,
            SyncJobRepository syncJobRepository,
            TransactionRecordRepository transactionRepository,
            CategoryRepository categoryRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock) {
        this.connectionRepository = connectionRepository;
        this.authorizationRepository = authorizationRepository;
        this.accountRepository = accountRepository;
        this.syncJobRepository = syncJobRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    UUID start(UUID userId, AuthorizedBankConnection access) {
        BankConnection connection = connectionRepository
                .findOwnedByIdForUpdate(access.connectionId(), userId)
                .orElseThrow(BankConnectionRequiredException::new);
        if (connection.getStatus() != BankConnection.Status.ACTIVE) {
            throw new BankReconnectionRequiredException();
        }
        if (connection.getSyncStatus() == BankConnection.SyncStatus.RUNNING
                || syncJobRepository.existsByBankConnection_IdAndStatusIn(
                        connection.getId(), ACTIVE_JOB_STATUSES)) {
            throw new BankSyncInProgressException();
        }

        BankConnectionAuthorization authorization = authorizationRepository
                .findById(access.authorizationId())
                .filter(candidate -> candidate.getBankConnection().getId().equals(connection.getId()))
                .filter(candidate -> candidate.isUsableAt(clock.instant()))
                .filter(candidate -> candidate.grants("ACCOUNTS_READ"))
                .filter(candidate -> candidate.grants("BALANCES_READ"))
                .filter(candidate -> candidate.grants("TRANSACTIONS_READ"))
                .orElseThrow(BankReconnectionRequiredException::new);

        Instant now = clock.instant();
        connection.startSynchronization(now);
        return syncJobRepository.saveAndFlush(new SyncJob(connection, authorization, now)).getId();
    }

    @Transactional
    SynchronizationSummary complete(
            UUID userId,
            UUID synchronizationId,
            BankSynchronizationBatch batch) {
        SyncJob job = syncJobRepository.findWithLockById(synchronizationId)
                .filter(candidate -> candidate.getStatus() == SyncJob.Status.RUNNING)
                .orElseThrow(BankSynchronizationFailedException::new);
        BankConnection connection = connectionRepository
                .findOwnedByIdForUpdate(job.getConnectionId(), userId)
                .filter(candidate -> candidate.getStatus() == BankConnection.Status.ACTIVE)
                .orElseThrow(BankConnectionRequiredException::new);
        AppUser user = userRepository.getReferenceById(userId);
        Category uncategorized = categoryRepository.getReferenceById(
                TransactionRecord.UNCATEGORIZED_CATEGORY_ID);
        Instant now = clock.instant();

        int accountsCreated = 0;
        int accountsUpdated = 0;
        int transactionsCreated = 0;
        int transactionsUpdated = 0;

        for (BankSynchronizationBatch.AccountSnapshot snapshot : batch.accounts()) {
            FinancialAccount account = accountRepository
                    .findByUser_IdAndProviderCodeAndExternalAccountIdAndFirstSynchronizedAtIsNotNull(
                            userId,
                            connection.getProviderCode(),
                            snapshot.account().externalAccountId())
                    .or(() -> accountRepository
                            .findTopByUser_IdAndProviderCodeAndExternalAccountIdOrderByUpdatedAtDesc(
                                    userId,
                                    connection.getProviderCode(),
                                    snapshot.account().externalAccountId()))
                    .orElse(null);
            if (account == null) {
                account = new FinancialAccount(user, connection, snapshot.account(), now);
                accountsCreated++;
            } else {
                accountsUpdated++;
            }
            account.synchronize(
                    connection,
                    snapshot.account(),
                    snapshot.balance().currentBalance(),
                    snapshot.balance().availableBalance(),
                    now);
            account = accountRepository.saveAndFlush(account);

            Map<String, TransactionRecord> existing = new HashMap<>();
            if (!snapshot.transactions().isEmpty()) {
                transactionRepository.findByFinancialAccount_IdAndExternalTransactionIdIn(
                                account.getId(),
                                snapshot.transactions().stream()
                                        .map(ExternalBankTransaction::externalTransactionId)
                                        .toList())
                        .forEach(transaction -> existing.put(
                                transaction.getExternalTransactionId(), transaction));
            }

            for (ExternalBankTransaction providerTransaction : snapshot.transactions()) {
                TransactionRecord transaction = existing.get(providerTransaction.externalTransactionId());
                TransactionRecord.Direction direction = TransactionRecord.Direction.valueOf(
                        providerTransaction.direction());
                String normalizedDescription = normalizeDescription(providerTransaction.description());
                if (transaction == null) {
                    transaction = new TransactionRecord(
                            user,
                            account,
                            uncategorized,
                            job,
                            providerTransaction.externalTransactionId(),
                            providerTransaction.bookingDate(),
                            providerTransaction.valueDate(),
                            providerTransaction.amount(),
                            direction,
                            providerTransaction.currency(),
                            providerTransaction.description(),
                            normalizedDescription,
                            providerTransaction.counterpartyName(),
                            providerTransaction.status(),
                            now);
                    transactionsCreated++;
                } else {
                    transaction.synchronize(
                            job,
                            providerTransaction.bookingDate(),
                            providerTransaction.valueDate(),
                            providerTransaction.amount(),
                            direction,
                            providerTransaction.currency(),
                            providerTransaction.description(),
                            normalizedDescription,
                            providerTransaction.counterpartyName(),
                            providerTransaction.status(),
                            now);
                    transactionsUpdated++;
                }
                transactionRepository.save(transaction);
            }
        }

        job.complete(
                batch.accounts().size(),
                batch.transactionCount(),
                accountsCreated,
                accountsUpdated,
                transactionsCreated,
                transactionsUpdated,
                now);
        connection.completeSynchronization(now);
        auditService.record(userId, "MOCK_BANK_SYNC_SUCCEEDED", "SYNC_JOB", job.getId());
        return toSummary(job, connection.getLastSyncedAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(UUID userId, UUID synchronizationId, String safeFailureCode) {
        SyncJob job = syncJobRepository.findWithLockById(synchronizationId).orElse(null);
        if (job == null || job.getStatus() != SyncJob.Status.RUNNING) {
            return;
        }
        Instant now = clock.instant();
        job.fail(safeFailureCode, now);
        connectionRepository.findOwnedByIdForUpdate(job.getConnectionId(), userId)
                .ifPresent(connection -> connection.failSynchronization(safeFailureCode, now));
        auditService.recordFailure(userId, "MOCK_BANK_SYNC_FAILED", "SYNC_JOB", job.getId());
    }

    @Transactional(readOnly = true)
    SynchronizationStatusResponse status(UUID userId) {
        BankConnection connection = connectionRepository
                .findTopByUser_IdAndProviderCodeOrderByCreatedAtDesc(
                        userId, BankConnection.MOCK_BANK_PROVIDER)
                .orElseThrow(BankConnectionRequiredException::new);
        SynchronizationSummary latest = syncJobRepository
                .findTopByBankConnection_IdOrderByCreatedAtDesc(connection.getId())
                .map(job -> toSummary(job, connection.getLastSyncedAt()))
                .orElse(null);
        return new SynchronizationStatusResponse(
                connection.getSyncStatus().name(),
                connection.getLastSyncAttemptedAt(),
                connection.getLastSyncedAt(),
                connection.getLastSyncFailureCode(),
                latest);
    }

    private SynchronizationSummary toSummary(SyncJob job, Instant lastSuccessfulAt) {
        return new SynchronizationSummary(
                job.getId(),
                job.getStatus().name(),
                job.getStartedAt(),
                job.getCompletedAt(),
                lastSuccessfulAt,
                job.getAccountsCreated(),
                job.getAccountsUpdated(),
                job.getTransactionsCreated(),
                job.getTransactionsUpdated());
    }

    private String normalizeDescription(String description) {
        return description.trim().replaceAll("\\s+", " ");
    }
}
