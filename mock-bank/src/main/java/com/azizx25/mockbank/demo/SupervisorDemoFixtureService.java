package com.azizx25.mockbank.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.account.persistence.BankAccountRepository;
import com.azizx25.mockbank.authorization.persistence.AccessTokenRepository;
import com.azizx25.mockbank.authorization.persistence.AuthorizationCodeRepository;
import com.azizx25.mockbank.consent.persistence.BankConsent;
import com.azizx25.mockbank.consent.persistence.BankConsentRepository;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.demo.SupervisorDemoScenario.Account;
import com.azizx25.mockbank.demo.SupervisorDemoScenario.Scenario;
import com.azizx25.mockbank.demo.SupervisorDemoScenario.TransactionSpec;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.ResetResponse;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.SeedResponse;
import com.azizx25.mockbank.exception.AdminConflictException;
import com.azizx25.mockbank.exception.InvalidAdminRequestException;
import com.azizx25.mockbank.transaction.persistence.BankTransaction;
import com.azizx25.mockbank.transaction.persistence.BankTransactionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo")
@ConditionalOnProperty(name = "app.demo-fixtures.enabled", havingValue = "true")
public class SupervisorDemoFixtureService {

    public static final String RESET_CONFIRMATION = "RESET_SUPERVISOR_DEMO_ONLY";

    private final BankCustomerRepository customerRepository;
    private final BankAccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankConsentRepository consentRepository;
    private final AuthorizationCodeRepository authorizationCodeRepository;
    private final AccessTokenRepository accessTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public SupervisorDemoFixtureService(
            BankCustomerRepository customerRepository,
            BankAccountRepository accountRepository,
            BankTransactionRepository transactionRepository,
            BankConsentRepository consentRepository,
            AuthorizationCodeRepository authorizationCodeRepository,
            AccessTokenRepository accessTokenRepository,
            PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.consentRepository = consentRepository;
        this.authorizationCodeRepository = authorizationCodeRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public SeedResponse seed(String scenarioVersion, LocalDate asOfDate, String rawPassword) {
        requirePassword(rawPassword);
        Scenario requested = SupervisorDemoScenario.generate(scenarioVersion, asOfDate);
        BankCustomer customer = customer(rawPassword, asOfDate);
        BankAccount current = account(
                customer,
                SupervisorDemoScenario.CURRENT_ACCOUNT_ID,
                "Compte courant Amine - Demo",
                "CHECKING",
                asOfDate);
        BankAccount savings = account(
                customer,
                SupervisorDemoScenario.SAVINGS_ACCOUNT_ID,
                "Epargne projets Amine - Demo",
                "SAVINGS",
                asOfDate);

        List<BankTransaction> previousCurrent = fixtureTransactions(current);
        List<BankTransaction> previousSavings = fixtureTransactions(savings);
        Scenario scenario = anchoredScenario(requested, previousCurrent, previousSavings);
        verifyBalances(current, previousCurrent);
        verifyBalances(savings, previousSavings);

        int created = 0;
        for (TransactionSpec spec : scenario.transactions()) {
            BankAccount target = spec.account() == Account.CURRENT ? current : savings;
            BankTransaction existing = transactionRepository
                    .findByBankAccountIdAndExternalTransactionId(
                            target.getId(), spec.externalReference())
                    .orElse(null);
            if (existing == null) {
                applyLedger(target, spec);
                transactionRepository.save(new BankTransaction(
                        target.getId(),
                        spec.externalReference(),
                        spec.bookingDate(),
                        spec.valueDate(),
                        spec.amount(),
                        spec.direction(),
                        "TND",
                        spec.description(),
                        spec.counterparty(),
                        spec.status(),
                        createdAt(spec.bookingDate())));
                created++;
            } else {
                verifyTransaction(existing, spec);
            }
        }
        transactionRepository.flush();
        accountRepository.flush();

        List<BankTransaction> currentTransactions = fixtureTransactions(current);
        List<BankTransaction> savingsTransactions = fixtureTransactions(savings);
        verifyBalances(current, currentTransactions);
        verifyBalances(savings, savingsTransactions);

        List<BankTransaction> allTransactions = java.util.stream.Stream
                .concat(currentTransactions.stream(), savingsTransactions.stream())
                .toList();
        int completedMonths = (int) allTransactions.stream()
                .filter(transaction -> "POSTED".equals(transaction.getStatus()))
                .map(transaction -> YearMonth.from(transaction.getBookingDate()))
                .filter(month -> month.isBefore(scenario.currentMonth()))
                .distinct()
                .count();
        int pending = (int) allTransactions.stream()
                .filter(transaction -> "PENDING".equals(transaction.getStatus()))
                .count();
        return new SeedResponse(
                scenario.version(), scenario.asOfDate(), customer.getExternalCustomerId(), customer.getUsername(),
                current.getExternalAccountId(), savings.getExternalAccountId(), 2, allTransactions.size(), created,
                completedMonths, pending, current.getCurrentBalance(), current.getAvailableBalance(),
                savings.getCurrentBalance());
    }

    @Transactional
    public ResetResponse reset(String scenarioVersion, String username, String confirmation) {
        requireExactResetIdentity(scenarioVersion, username, confirmation);
        BankCustomer customer = customerRepository
                .findByExternalCustomerId(SupervisorDemoScenario.CUSTOMER_ID)
                .orElse(null);
        if (customer == null) {
            return new ResetResponse(scenarioVersion, username, false, 0, 0);
        }
        verifyCustomerIdentity(customer);
        List<BankAccount> accounts = accountRepository
                .findByBankCustomerIdOrderByExternalAccountIdAsc(customer.getId());
        if (accounts.stream().anyMatch(account -> !isFixtureAccount(account))) {
            throw new AdminConflictException("Supervisor demo reset refused because non-demo accounts exist");
        }
        List<BankTransaction> transactions = accounts.stream()
                .flatMap(account -> transactionRepository
                        .findByBankAccountIdOrderByBookingDateAscExternalTransactionIdAsc(account.getId())
                        .stream())
                .toList();
        if (transactions.stream().anyMatch(transaction -> !isFixtureReference(
                transaction.getExternalTransactionId()))) {
            throw new AdminConflictException("Supervisor demo reset refused because non-demo transactions exist");
        }

        List<BankConsent> consents = consentRepository.findByBankCustomer_Id(customer.getId());
        for (BankConsent consent : consents) {
            accessTokenRepository.deleteByBankConsent_Id(consent.getId());
            authorizationCodeRepository.deleteByBankConsent_Id(consent.getId());
        }
        consentRepository.deleteAll(consents);
        for (BankAccount account : accounts) {
            transactionRepository.deleteByBankAccountId(account.getId());
        }
        accountRepository.deleteAll(accounts);
        customerRepository.delete(customer);
        customerRepository.flush();
        return new ResetResponse(scenarioVersion, username, true, accounts.size(), transactions.size());
    }

    void requireExactResetIdentity(String scenarioVersion, String username, String confirmation) {
        if (!SupervisorDemoScenario.VERSION.equals(scenarioVersion)
                || !SupervisorDemoScenario.USERNAME.equals(username)
                || !RESET_CONFIRMATION.equals(confirmation)) {
            throw new InvalidAdminRequestException("Supervisor demo reset identity confirmation failed");
        }
    }

    private Scenario anchoredScenario(
            Scenario requested,
            List<BankTransaction> currentTransactions,
            List<BankTransaction> savingsTransactions) {
        if (currentTransactions.isEmpty() && savingsTransactions.isEmpty()) {
            return requested;
        }
        BankTransaction currentOpening = requireOpening(currentTransactions, "OPENING-CURRENT");
        BankTransaction savingsOpening = requireOpening(savingsTransactions, "OPENING-SAVINGS");
        YearMonth firstMonth = YearMonth.from(currentOpening.getBookingDate());
        if (!currentOpening.getBookingDate().equals(firstMonth.atDay(1))
                || !savingsOpening.getBookingDate().equals(currentOpening.getBookingDate())
                || !currentOpening.getExternalTransactionId().equals(
                        SupervisorDemoScenario.REFERENCE_PREFIX + firstMonth + "-OPENING-CURRENT")
                || !savingsOpening.getExternalTransactionId().equals(
                        SupervisorDemoScenario.REFERENCE_PREFIX + firstMonth + "-OPENING-SAVINGS")
                || firstMonth.isAfter(requested.firstCompletedMonth())) {
            throw new AdminConflictException("Supervisor demo history anchor could not be verified");
        }
        if (java.util.stream.Stream.concat(currentTransactions.stream(), savingsTransactions.stream())
                .anyMatch(transaction -> transaction.getBookingDate().isAfter(requested.asOfDate()))) {
            throw new AdminConflictException("Supervisor demo as-of date precedes existing transaction history");
        }
        return SupervisorDemoScenario.generate(requested.version(), requested.asOfDate(), firstMonth);
    }

    private BankTransaction requireOpening(List<BankTransaction> transactions, String type) {
        List<BankTransaction> openings = transactions.stream()
                .filter(transaction -> transaction.getExternalTransactionId().endsWith("-" + type))
                .toList();
        if (openings.size() != 1) {
            throw new AdminConflictException("Supervisor demo history anchor could not be verified");
        }
        return openings.getFirst();
    }

    private BankCustomer customer(String rawPassword, LocalDate asOfDate) {
        BankCustomer byExternal = customerRepository
                .findByExternalCustomerId(SupervisorDemoScenario.CUSTOMER_ID)
                .orElse(null);
        BankCustomer byUsername = customerRepository
                .findByUsername(SupervisorDemoScenario.USERNAME)
                .orElse(null);
        if (byExternal == null && byUsername == null) {
            return customerRepository.save(new BankCustomer(
                    SupervisorDemoScenario.CUSTOMER_ID,
                    SupervisorDemoScenario.DISPLAY_NAME,
                    SupervisorDemoScenario.USERNAME,
                    passwordEncoder.encode(rawPassword),
                    SupervisorDemoScenario.COUNTRY_CODE,
                    createdAt(asOfDate.minusMonths(SupervisorDemoScenario.COMPLETED_MONTH_COUNT))));
        }
        if (byExternal == null || byUsername == null || !byExternal.getId().equals(byUsername.getId())) {
            throw new AdminConflictException("Supervisor demo identity conflicts with existing customer data");
        }
        verifyCustomerIdentity(byExternal);
        if (!passwordEncoder.matches(rawPassword, byExternal.getPasswordHash())) {
            throw new AdminConflictException("Existing supervisor demo customer could not be verified");
        }
        return byExternal;
    }

    private void verifyCustomerIdentity(BankCustomer customer) {
        if (!SupervisorDemoScenario.CUSTOMER_ID.equals(customer.getExternalCustomerId())
                || !SupervisorDemoScenario.USERNAME.equals(customer.getUsername())
                || !SupervisorDemoScenario.DISPLAY_NAME.equals(customer.getDisplayName())
                || !SupervisorDemoScenario.COUNTRY_CODE.equals(customer.getCountryCode())
                || !"ACTIVE".equals(customer.getStatus())) {
            throw new AdminConflictException("Existing supervisor demo customer does not match the fixed identity");
        }
    }

    private BankAccount account(
            BankCustomer customer,
            String externalAccountId,
            String displayName,
            String accountType,
            LocalDate asOfDate) {
        BankAccount account = accountRepository.findByExternalAccountIdForUpdate(externalAccountId).orElse(null);
        if (account == null) {
            return accountRepository.save(new BankAccount(
                    customer.getId(), externalAccountId, displayName, accountType, "TND",
                    createdAt(asOfDate.minusMonths(SupervisorDemoScenario.COMPLETED_MONTH_COUNT))));
        }
        if (!customer.getId().equals(account.getBankCustomerId())
                || !displayName.equals(account.getDisplayName())
                || !accountType.equals(account.getAccountType())
                || !"TND".equals(account.getCurrency())
                || !"ACTIVE".equals(account.getStatus())) {
            throw new AdminConflictException("Supervisor demo account conflicts with existing data");
        }
        return account;
    }

    private void applyLedger(BankAccount account, TransactionSpec spec) {
        if ("PENDING".equals(spec.status())) {
            if ("DEBIT".equals(spec.direction())) {
                account.reserveDebit(spec.amount());
            }
            return;
        }
        if ("CREDIT".equals(spec.direction())) {
            account.applyCredit(spec.amount());
        } else {
            account.applyDebit(spec.amount());
        }
    }

    private List<BankTransaction> fixtureTransactions(BankAccount account) {
        List<BankTransaction> transactions = transactionRepository
                .findByBankAccountIdOrderByBookingDateAscExternalTransactionIdAsc(account.getId());
        if (transactions.stream().anyMatch(transaction -> !isFixtureReference(
                transaction.getExternalTransactionId()))) {
            throw new AdminConflictException("Supervisor demo account contains non-demo transactions");
        }
        return transactions;
    }

    private void verifyBalances(BankAccount account, List<BankTransaction> transactions) {
        BigDecimal current = BigDecimal.ZERO.setScale(4);
        BigDecimal available = BigDecimal.ZERO.setScale(4);
        for (BankTransaction transaction : transactions) {
            if ("POSTED".equals(transaction.getStatus())) {
                BigDecimal signed = "CREDIT".equals(transaction.getDirection())
                        ? transaction.getAmount() : transaction.getAmount().negate();
                current = current.add(signed);
                available = available.add(signed);
            } else if ("PENDING".equals(transaction.getStatus())
                    && "DEBIT".equals(transaction.getDirection())) {
                available = available.subtract(transaction.getAmount());
            }
        }
        if (current.compareTo(account.getCurrentBalance()) != 0
                || available.compareTo(account.getAvailableBalance()) != 0) {
            throw new AdminConflictException("Supervisor demo account balance does not reconcile with its ledger");
        }
    }

    private void verifyTransaction(BankTransaction transaction, TransactionSpec spec) {
        if (!spec.bookingDate().equals(transaction.getBookingDate())
                || !Objects.equals(spec.valueDate(), transaction.getValueDate())
                || spec.amount().compareTo(transaction.getAmount()) != 0
                || !spec.direction().equals(transaction.getDirection())
                || !"TND".equals(transaction.getCurrency())
                || !spec.description().equals(transaction.getDescription())
                || !Objects.equals(spec.counterparty(), transaction.getCounterpartyName())
                || !spec.status().equals(transaction.getStatus())) {
            throw new AdminConflictException("Supervisor demo transaction conflicts with existing data");
        }
    }

    private boolean isFixtureAccount(BankAccount account) {
        return SupervisorDemoScenario.CURRENT_ACCOUNT_ID.equals(account.getExternalAccountId())
                || SupervisorDemoScenario.SAVINGS_ACCOUNT_ID.equals(account.getExternalAccountId());
    }

    private boolean isFixtureReference(String value) {
        return value != null && value.startsWith(SupervisorDemoScenario.REFERENCE_PREFIX);
    }

    private void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidAdminRequestException("Supervisor demo customer password is required");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72) {
            throw new InvalidAdminRequestException(
                    "Supervisor demo customer password must contain between 12 and 72 UTF-8 bytes");
        }
    }

    private OffsetDateTime createdAt(LocalDate date) {
        return date.atTime(12, 0).atOffset(ZoneOffset.UTC);
    }
}
