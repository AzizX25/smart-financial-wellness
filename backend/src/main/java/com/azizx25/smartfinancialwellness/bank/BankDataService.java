package com.azizx25.smartfinancialwellness.bank;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.azizx25.smartfinancialwellness.bank.BankConnectionService.AuthorizedBankConnection;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankAccountResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankBalanceResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankTransactionPageResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankTransactionResponse;
import com.azizx25.smartfinancialwellness.bank.exception.BankAccountNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.BankReconnectionRequiredException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankAuthenticationException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnection;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BankDataService {

    private static final String ACCOUNTS_READ = "ACCOUNTS_READ";
    private static final String BALANCES_READ = "BALANCES_READ";
    private static final String TRANSACTIONS_READ = "TRANSACTIONS_READ";
    private static final Set<String> ACCOUNT_TYPES =
            Set.of("CHECKING", "SAVINGS", "CASH", "OTHER");

    private final ReadOnlyBankDataPort bankDataPort;
    private final BankConnectionService connectionService;
    private final FinancialAccountRepository accountRepository;
    private final BankConnectionRepository connectionRepository;
    private final AppUserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BankDataService(
            ReadOnlyBankDataPort bankDataPort,
            BankConnectionService connectionService,
            FinancialAccountRepository accountRepository,
            BankConnectionRepository connectionRepository,
            AppUserRepository userRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.bankDataPort = bankDataPort;
        this.connectionService = connectionService;
        this.accountRepository = accountRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public List<BankAccountResponse> listAccounts(UUID userId) {
        AuthorizedBankConnection access = connectionService.requireAccess(userId, ACCOUNTS_READ);
        List<ExternalBankAccount> externalAccounts = authorizedCall(
                userId,
                access,
                () -> bankDataPort.listAccounts(access.credentials()));
        validateAccounts(externalAccounts);
        return transactionTemplate.execute(status -> upsertAccounts(userId, access, externalAccounts));
    }

    public BankAccountResponse getAccount(UUID userId, UUID accountId) {
        AuthorizedBankConnection access = connectionService.requireAccess(userId, ACCOUNTS_READ);
        OwnedAccount ownedAccount = loadOwnedAccount(userId, accountId, access.connectionId());
        ExternalBankAccount externalAccount = authorizedCall(
                userId,
                access,
                () -> bankDataPort.getAccount(access.credentials(), ownedAccount.externalAccountId()));
        validateAccount(externalAccount);
        requireMatchingExternalAccount(ownedAccount.externalAccountId(), externalAccount.externalAccountId());
        return transactionTemplate.execute(status -> {
            FinancialAccount account = accountRepository.findByIdAndUser_Id(accountId, userId)
                    .orElseThrow(BankAccountNotFoundException::new);
            account.refreshMetadata(externalAccount);
            return toAccountResponse(account);
        });
    }

    public BankBalanceResponse getBalance(UUID userId, UUID accountId) {
        AuthorizedBankConnection access = connectionService.requireAccess(userId, BALANCES_READ);
        OwnedAccount account = loadOwnedAccount(userId, accountId, access.connectionId());
        ExternalBankBalance balance = authorizedCall(
                userId,
                access,
                () -> bankDataPort.getBalance(access.credentials(), account.externalAccountId()));
        requireMatchingExternalAccount(account.externalAccountId(), balance.externalAccountId());
        if (!account.currency().equals(balance.currency())) {
            throw new MockBankProtocolException();
        }
        transactionTemplate.executeWithoutResult(status -> accountRepository
                .findByIdAndUser_Id(accountId, userId)
                .orElseThrow(BankAccountNotFoundException::new)
                .updateBalance(balance.currentBalance(), balance.availableBalance()));
        return new BankBalanceResponse(
                accountId,
                balance.currency(),
                balance.currentBalance(),
                balance.availableBalance(),
                balance.status(),
                balance.maskedAccountReference());
    }

    public BankTransactionPageResponse listTransactions(
            UUID userId,
            UUID accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        AuthorizedBankConnection access = connectionService.requireAccess(userId, TRANSACTIONS_READ);
        OwnedAccount account = loadOwnedAccount(userId, accountId, access.connectionId());
        ExternalBankTransactionPage result = authorizedCall(
                userId,
                access,
                () -> bankDataPort.listTransactions(
                        access.credentials(),
                        account.externalAccountId(),
                        from,
                        to,
                        page,
                        size));
        return new BankTransactionPageResponse(
                result.items().stream()
                        .map(transaction -> new BankTransactionResponse(
                                transaction.externalTransactionId(),
                                transaction.bookingDate(),
                                transaction.valueDate(),
                                transaction.amount(),
                                transaction.direction(),
                                transaction.currency(),
                                transaction.description(),
                                transaction.counterpartyName(),
                                transaction.status()))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.first(),
                result.last());
    }

    private List<BankAccountResponse> upsertAccounts(
            UUID userId,
            AuthorizedBankConnection access,
            List<ExternalBankAccount> externalAccounts) {
        AppUser user = userRepository.getReferenceById(userId);
        BankConnection connection = connectionRepository.getReferenceById(access.connectionId());
        return externalAccounts.stream()
                .map(external -> {
                    FinancialAccount account = accountRepository
                            .findByUser_IdAndProviderCodeAndExternalAccountIdAndFirstSynchronizedAtIsNotNull(
                                    userId,
                                    BankConnection.MOCK_BANK_PROVIDER,
                                    external.externalAccountId())
                            .or(() -> accountRepository
                                    .findTopByUser_IdAndProviderCodeAndExternalAccountIdOrderByUpdatedAtDesc(
                                            userId,
                                            BankConnection.MOCK_BANK_PROVIDER,
                                            external.externalAccountId()))
                            .orElseGet(() -> new FinancialAccount(
                                    user,
                                    connection,
                                    external,
                                    clock.instant()));
                    account.refreshConnection(connection, external);
                    return toAccountResponse(accountRepository.save(account));
                })
                .toList();
    }

    private OwnedAccount loadOwnedAccount(UUID userId, UUID accountId, UUID connectionId) {
        return transactionTemplate.execute(status -> {
            FinancialAccount account = accountRepository.findByIdAndUser_Id(accountId, userId)
                    .filter(candidate -> candidate.getBankConnection() != null)
                    .filter(candidate -> candidate.getBankConnection().getId().equals(connectionId))
                    .filter(candidate -> candidate.getStatus() != FinancialAccount.Status.DISCONNECTED)
                    .orElseThrow(BankAccountNotFoundException::new);
            return new OwnedAccount(
                    account.getExternalAccountId(),
                    account.getCurrency());
        });
    }

    private <T> T authorizedCall(
            UUID userId,
            AuthorizedBankConnection access,
            Supplier<T> request) {
        try {
            return request.get();
        } catch (MockBankAuthenticationException exception) {
            connectionService.invalidateProviderAuthentication(userId, access.connectionId());
            throw new BankReconnectionRequiredException();
        }
    }

    private void validateAccounts(List<ExternalBankAccount> accounts) {
        if (accounts == null) {
            throw new MockBankProtocolException();
        }
        accounts.forEach(this::validateAccount);
    }

    private void validateAccount(ExternalBankAccount account) {
        if (account == null
                || !ACCOUNT_TYPES.contains(account.accountType())
                || account.externalAccountId().isBlank()
                || account.displayName().isBlank()
                || account.currency().length() != 3
                || account.maskedAccountReference().isBlank()) {
            throw new MockBankProtocolException();
        }
    }

    private void requireMatchingExternalAccount(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new MockBankProtocolException();
        }
    }

    private BankAccountResponse toAccountResponse(FinancialAccount account) {
        return new BankAccountResponse(
                account.getId(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getMaskedIdentifier(),
                account.getStatus().name(),
                account.getCreatedAt());
    }

    private record OwnedAccount(String externalAccountId, String currency) {
    }
}
