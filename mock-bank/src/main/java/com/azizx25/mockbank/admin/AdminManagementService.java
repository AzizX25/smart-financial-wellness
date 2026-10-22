package com.azizx25.mockbank.admin;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.account.persistence.BankAccountRepository;
import com.azizx25.mockbank.admin.api.AdminDtos.AccountResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateAccountRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateCustomerRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateTransactionRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CustomerResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.DashboardResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.PageResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.TransactionResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.UpdateAccountRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.UpdateCustomerRequest;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.AdminConflictException;
import com.azizx25.mockbank.exception.AdminOperationRejectedException;
import com.azizx25.mockbank.exception.AdminResourceNotFoundException;
import com.azizx25.mockbank.exception.InvalidAdminRequestException;
import com.azizx25.mockbank.transaction.persistence.BankTransaction;
import com.azizx25.mockbank.transaction.persistence.BankTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "INACTIVE", "CLOSED");
    private static final Set<String> ACCOUNT_TYPES = Set.of("CHECKING", "SAVINGS", "CASH", "OTHER");

    private final BankCustomerRepository customerRepository;
    private final BankAccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminManagementService(
            BankCustomerRepository customerRepository,
            BankAccountRepository accountRepository,
            BankTransactionRepository transactionRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        Page<BankTransaction> recent = transactionRepository.findAllByOrderByBookingDateDescCreatedAtDesc(
                PageRequest.of(0, 5));
        return new DashboardResponse(
                customerRepository.count(),
                customerRepository.countByStatus("ACTIVE"),
                customerRepository.countByStatus("INACTIVE"),
                accountRepository.count(),
                accountRepository.countByStatus("ACTIVE"),
                accountRepository.countByStatus("INACTIVE"),
                accountRepository.countByStatus("CLOSED"),
                transactionRepository.count(),
                recent.getContent().stream().map(this::toTransactionResponse).toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> listCustomers(String search, int page, int size) {
        validatePagination(page, size);
        String normalizedSearch = search == null || search.isBlank()
                ? null
                : "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
        Page<BankCustomer> customers = customerRepository.search(
                normalizedSearch,
                PageRequest.of(page, size, Sort.by("createdAt").descending()
                        .and(Sort.by("externalCustomerId").ascending())));
        return page(customers.map(this::toCustomerResponse));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(String customerId) {
        return toCustomerResponse(requireCustomer(customerId));
    }

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        String username = normalizeUsername(request.username());
        validatePassword(request.password());
        if (customerRepository.existsByUsername(username)) {
            throw new AdminConflictException("Username is already in use");
        }
        BankCustomer customer = new BankCustomer(
                generatedId("MOCK-CUST"),
                request.displayName().strip(),
                username,
                passwordEncoder.encode(request.password()),
                request.countryCode().toUpperCase(Locale.ROOT),
                now());
        try {
            return toCustomerResponse(customerRepository.saveAndFlush(customer));
        } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("Customer conflicts with existing data");
        }
    }

    @Transactional
    public CustomerResponse updateCustomer(String customerId, UpdateCustomerRequest request) {
        if (request.displayName() == null && request.username() == null && request.countryCode() == null) {
            throw new InvalidAdminRequestException("At least one customer field is required");
        }
        BankCustomer customer = requireCustomer(customerId);
        String username = request.username() == null
                ? customer.getUsername()
                : normalizeUsername(request.username());
        customerRepository.findByUsername(username)
                .filter(other -> !other.getId().equals(customer.getId()))
                .ifPresent(other -> {
                    throw new AdminConflictException("Username is already in use");
                });
        customer.updateProfile(
                request.displayName() == null ? customer.getDisplayName() : request.displayName().strip(),
                username,
                request.countryCode() == null
                        ? customer.getCountryCode()
                        : request.countryCode().toUpperCase(Locale.ROOT));
        try {
            customerRepository.flush();
            return toCustomerResponse(customer);
        } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("Customer conflicts with existing data");
        }
    }

    @Transactional
    public void resetCustomerPassword(String customerId, String password) {
        validatePassword(password);
        requireCustomer(customerId).resetPassword(passwordEncoder.encode(password));
    }

    @Transactional
    public CustomerResponse activateCustomer(String customerId) {
        BankCustomer customer = requireCustomer(customerId);
        customer.activate();
        return toCustomerResponse(customer);
    }

    @Transactional
    public CustomerResponse deactivateCustomer(String customerId) {
        BankCustomer customer = requireCustomer(customerId);
        customer.deactivate();
        return toCustomerResponse(customer);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listAccounts(
            String customerId,
            String status,
            String accountType,
            int page,
            int size) {
        validatePagination(page, size);
        UUID customerDatabaseId = customerId == null || customerId.isBlank()
                ? null
                : requireCustomer(customerId).getId();
        String normalizedStatus = normalizeFilter(status, ACCOUNT_STATUSES, "account status");
        String normalizedType = normalizeFilter(accountType, ACCOUNT_TYPES, "account type");
        Page<BankAccount> accounts = accountRepository.search(
                customerDatabaseId,
                normalizedStatus,
                normalizedType,
                PageRequest.of(page, size, Sort.by("createdAt").descending()
                        .and(Sort.by("externalAccountId").ascending())));
        return page(accounts.map(this::toAccountResponse));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        return toAccountResponse(requireAccount(accountId));
    }

    @Transactional
    public AccountResponse createAccount(String customerId, CreateAccountRequest request) {
        BankCustomer customer = requireCustomer(customerId);
        BankAccount account = new BankAccount(
                customer.getId(),
                generatedId("MOCK-ACC"),
                request.displayName().strip(),
                request.accountType(),
                request.currency().toUpperCase(Locale.ROOT),
                now());
        return toAccountResponse(accountRepository.saveAndFlush(account));
    }

    @Transactional
    public AccountResponse updateAccount(String accountId, UpdateAccountRequest request) {
        if (request.displayName() == null && request.accountType() == null) {
            throw new InvalidAdminRequestException("At least one account field is required");
        }
        BankAccount account = requireAccount(accountId);
        account.updateDetails(
                request.displayName() == null ? account.getDisplayName() : request.displayName().strip(),
                request.accountType() == null ? account.getAccountType() : request.accountType());
        return toAccountResponse(account);
    }

    @Transactional
    public AccountResponse activateAccount(String accountId) {
        BankAccount account = requireAccount(accountId);
        if ("CLOSED".equals(account.getStatus())) {
            throw new AdminOperationRejectedException("A closed account cannot be reactivated");
        }
        account.activate();
        return toAccountResponse(account);
    }

    @Transactional
    public AccountResponse freezeAccount(String accountId) {
        BankAccount account = requireAccount(accountId);
        if ("CLOSED".equals(account.getStatus())) {
            throw new AdminOperationRejectedException("A closed account cannot be frozen");
        }
        account.freeze();
        return toAccountResponse(account);
    }

    @Transactional
    public AccountResponse closeAccount(String accountId) {
        BankAccount account = requireAccount(accountId);
        account.close();
        return toAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> listTransactions(
            String accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        validatePagination(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidAdminRequestException("The from date must not be after the to date");
        }
        BankAccount account = requireAccount(accountId);
        PageRequest request = PageRequest.of(page, size);
        Page<BankTransaction> transactions;
        if (from != null && to != null) {
            transactions = transactionRepository
                    .findByBankAccountIdAndBookingDateBetweenOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), from, to, request);
        } else if (from != null) {
            transactions = transactionRepository
                    .findByBankAccountIdAndBookingDateGreaterThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), from, request);
        } else if (to != null) {
            transactions = transactionRepository
                    .findByBankAccountIdAndBookingDateLessThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), to, request);
        } else {
            transactions = transactionRepository
                    .findByBankAccountIdOrderByBookingDateDescExternalTransactionIdDesc(account.getId(), request);
        }
        return page(transactions.map(this::toTransactionResponse));
    }

    @Transactional
    public TransactionResponse createTransaction(
            String accountId,
            CreateTransactionRequest request) {
        BankAccount account = accountRepository.findByExternalAccountIdForUpdate(accountId)
                .orElseThrow(() -> new AdminResourceNotFoundException("Account not found"));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new AdminOperationRejectedException("Transactions require an active account");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 4) {
            throw new InvalidAdminRequestException("Amount must be positive with at most four decimals");
        }
        if ("DEBIT".equals(request.direction())) {
            if (account.getCurrentBalance().compareTo(amount) < 0
                    || account.getAvailableBalance().compareTo(amount) < 0) {
                throw new AdminOperationRejectedException("Insufficient funds");
            }
            account.applyDebit(amount);
        } else {
            account.applyCredit(amount);
        }
        String reference = request.reference() == null || request.reference().isBlank()
                ? generatedId("MOCK-TXN")
                : request.reference().strip();
        BankTransaction transaction = new BankTransaction(
                account.getId(),
                reference,
                request.bookingDate() == null
                        ? LocalDate.now(clock)
                        : request.bookingDate(),
                amount,
                request.direction(),
                account.getCurrency(),
                request.description().strip(),
                request.counterpartyName() == null || request.counterpartyName().isBlank()
                        ? null
                        : request.counterpartyName().strip(),
                now());
        try {
            return toTransactionResponse(transactionRepository.saveAndFlush(transaction));
        } catch (DataIntegrityViolationException exception) {
            throw new AdminConflictException("Transaction reference is already in use for this account");
        }
    }

    private BankCustomer requireCustomer(String customerId) {
        return customerRepository.findByExternalCustomerId(customerId)
                .orElseThrow(() -> new AdminResourceNotFoundException("Customer not found"));
    }

    private BankAccount requireAccount(String accountId) {
        return accountRepository.findByExternalAccountId(accountId)
                .orElseThrow(() -> new AdminResourceNotFoundException("Account not found"));
    }

    private CustomerResponse toCustomerResponse(BankCustomer customer) {
        return new CustomerResponse(
                customer.getExternalCustomerId(),
                customer.getDisplayName(),
                customer.getUsername(),
                customer.getCountryCode(),
                customer.getStatus(),
                customer.getCreatedAt());
    }

    private AccountResponse toAccountResponse(BankAccount account) {
        BankCustomer customer = customerRepository.findById(account.getBankCustomerId())
                .orElseThrow(() -> new AdminResourceNotFoundException("Customer not found"));
        return new AccountResponse(
                account.getExternalAccountId(),
                customer.getExternalCustomerId(),
                account.getDisplayName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                account.getStatus(),
                account.getCreatedAt());
    }

    private TransactionResponse toTransactionResponse(BankTransaction transaction) {
        BankAccount account = accountRepository.findById(transaction.getBankAccountId())
                .orElseThrow(() -> new AdminResourceNotFoundException("Account not found"));
        return new TransactionResponse(
                transaction.getExternalTransactionId(),
                account.getExternalAccountId(),
                transaction.getBookingDate(),
                transaction.getValueDate(),
                transaction.getAmount(),
                transaction.getDirection(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getCounterpartyName(),
                transaction.getStatus(),
                transaction.getCreatedAt());
    }

    private <T> PageResponse<T> page(Page<T> result) {
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidAdminRequestException(
                    "Page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String normalizeFilter(String value, Set<String> accepted, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!accepted.contains(normalized)) {
            throw new InvalidAdminRequestException("Invalid " + label);
        }
        return normalized;
    }

    private String normalizeUsername(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidAdminRequestException("Password exceeds BCrypt's 72-byte limit");
        }
    }

    private String generatedId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
