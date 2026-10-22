package com.azizx25.mockbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azizx25.mockbank.account.AccountQueryService;
import com.azizx25.mockbank.account.AccountSummary;
import com.azizx25.mockbank.account.BalanceQueryService;
import com.azizx25.mockbank.account.BalanceSummary;
import com.azizx25.mockbank.client.OAuthClientLookupService;
import com.azizx25.mockbank.client.OAuthClientSummary;
import com.azizx25.mockbank.customer.CustomerLookupService;
import com.azizx25.mockbank.customer.CustomerSummary;
import com.azizx25.mockbank.exception.AccountNotFoundException;
import com.azizx25.mockbank.exception.AccountNotOwnedException;
import com.azizx25.mockbank.exception.ClientInactiveException;
import com.azizx25.mockbank.exception.ClientNotFoundException;
import com.azizx25.mockbank.exception.CustomerNotFoundException;
import com.azizx25.mockbank.exception.InvalidRedirectUriException;
import com.azizx25.mockbank.exception.InvalidTransactionDateRangeException;
import com.azizx25.mockbank.exception.InvalidTransactionPaginationException;
import com.azizx25.mockbank.transaction.PaginatedTransactionResult;
import com.azizx25.mockbank.transaction.TransactionQueryService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
class MockBankApplicationTests {

    private static final String SCHEMA = "mock_bank.";

    private static final UUID CUSTOMER_ONE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_TWO_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLIENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CHECKING_ACCOUNT_ID = UUID.fromString("44444444-4444-4444-4444-444444444401");
    private static final UUID SAVINGS_ACCOUNT_ID = UUID.fromString("44444444-4444-4444-4444-444444444402");
    private static final UUID SECOND_CUSTOMER_ACCOUNT_ID = UUID.fromString("44444444-4444-4444-4444-444444444403");
    private static final UUID SALARY_TRANSACTION_ID = UUID.fromString("55555555-5555-5555-5555-555555555501");
    private static final UUID GROCERY_TRANSACTION_ID = UUID.fromString("55555555-5555-5555-5555-555555555502");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerLookupService customerLookupService;

    @Autowired
    private OAuthClientLookupService oAuthClientLookupService;

    @Autowired
    private AccountQueryService accountQueryService;

    @Autowired
    private BalanceQueryService balanceQueryService;

    @Autowired
    private TransactionQueryService transactionQueryService;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointIsAvailableAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayAppliedIndependentMigrationsAndHibernateValidatedSchema() {
        List<String> successfulVersions = jdbcTemplate.queryForList(
                "SELECT version FROM " + SCHEMA + "flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        Set<String> applicationTables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'mock_bank' "
                        + "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'",
                String.class));

        assertThat(successfulVersions).containsExactly("1", "2", "3", "4");
        assertThat(applicationTables).containsExactlyInAnyOrder(
                "bank_customer",
                "oauth_client",
                "bank_account",
                "bank_transaction",
                "bank_consent",
                "authorization_code",
                "access_token",
                "bank_administrator");
    }

    @Test
    void seededCustomersAccountsAndTransactionsExistWithFixedIdentifiers() {
        Integer customers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "bank_customer",
                Integer.class);
        Integer accounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "bank_account",
                Integer.class);
        Integer transactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "bank_transaction",
                Integer.class);
        UUID actualCustomerOneId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "bank_customer WHERE external_customer_id = 'MOCK-CUST-001'",
                UUID.class);
        UUID actualCustomerTwoId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "bank_customer WHERE external_customer_id = 'MOCK-CUST-002'",
                UUID.class);
        UUID actualAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "bank_account WHERE external_account_id = 'MOCK-ACC-001'",
                UUID.class);
        UUID actualTransactionId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "bank_transaction WHERE external_transaction_id = 'MOCK-TXN-001'",
                UUID.class);
        List<UUID> accountIds = jdbcTemplate.queryForList(
                "SELECT id FROM " + SCHEMA + "bank_account ORDER BY external_account_id",
                UUID.class);
        List<UUID> transactionIds = jdbcTemplate.queryForList(
                "SELECT id FROM " + SCHEMA + "bank_transaction ORDER BY external_transaction_id",
                UUID.class);

        assertThat(customers).isEqualTo(2);
        assertThat(accounts).isEqualTo(3);
        assertThat(transactions).isEqualTo(8);
        assertThat(actualCustomerOneId).isEqualTo(CUSTOMER_ONE_ID);
        assertThat(actualCustomerTwoId).isEqualTo(CUSTOMER_TWO_ID);
        assertThat(actualAccountId).isEqualTo(CHECKING_ACCOUNT_ID);
        assertThat(actualTransactionId).isEqualTo(SALARY_TRANSACTION_ID);
        assertThat(accountIds).containsExactly(
                CHECKING_ACCOUNT_ID,
                SAVINGS_ACCOUNT_ID,
                SECOND_CUSTOMER_ACCOUNT_ID);
        assertThat(transactionIds).containsExactly(
                UUID.fromString("55555555-5555-5555-5555-555555555501"),
                UUID.fromString("55555555-5555-5555-5555-555555555502"),
                UUID.fromString("55555555-5555-5555-5555-555555555503"),
                UUID.fromString("55555555-5555-5555-5555-555555555504"),
                UUID.fromString("55555555-5555-5555-5555-555555555505"),
                UUID.fromString("55555555-5555-5555-5555-555555555506"),
                UUID.fromString("55555555-5555-5555-5555-555555555507"),
                UUID.fromString("55555555-5555-5555-5555-555555555508"));
    }

    @Test
    void importantUniquenessAndForeignKeyConstraintsWork() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_customer (id, external_customer_id, display_name, country_code) "
                        + "VALUES (?, 'MOCK-CUST-001', 'Duplicate customer', 'TN')",
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_account (id, bank_customer_id, external_account_id, display_name, "
                        + "account_type, currency, current_balance, available_balance, status) VALUES (?, ?, ?, ?, "
                        + "'CHECKING', 'TND', 0, 0, 'ACTIVE')",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BROKEN-ACCOUNT",
                "Broken account"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_transaction (id, bank_account_id, external_transaction_id, "
                        + "booking_date, amount, direction, currency, description, status) VALUES (?, ?, ?, CURRENT_DATE, "
                        + "10, 'DEBIT', 'TND', 'Broken transaction', 'POSTED')",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BROKEN-TXN"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_consent (id, bank_customer_id, oauth_client_id, granted_scopes, "
                        + "status, consented_at, expires_at) VALUES (?, ?, ?, ARRAY['accounts:read'], 'ACTIVE', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')",
                UUID.randomUUID(),
                CUSTOMER_ONE_ID,
                UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void mainApplicationDatabaseIsNotRequiredByMockBankTests() {
        Integer mockBankTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'mock_bank'",
                Integer.class);

        assertThat(mockBankTables).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('smart_financial_wellness.flyway_schema_history')",
                String.class)).isNull();
    }

    @Test
    void deterministicSyntheticDataAreReadable() {
        String customerName = jdbcTemplate.queryForObject(
                "SELECT display_name FROM " + SCHEMA + "bank_customer WHERE id = ?",
                String.class,
                CUSTOMER_ONE_ID);
        String currentBalance = jdbcTemplate.queryForObject(
                "SELECT current_balance::text FROM " + SCHEMA + "bank_account WHERE id = ?",
                String.class,
                CHECKING_ACCOUNT_ID);
        String transactionDescription = jdbcTemplate.queryForObject(
                "SELECT description FROM " + SCHEMA + "bank_transaction WHERE id = ?",
                String.class,
                GROCERY_TRANSACTION_ID);

        assertThat(customerName).isEqualTo("Amina Ben Romdhane");
        assertThat(currentBalance).isEqualTo("3626.2500");
        assertThat(transactionDescription).isEqualTo("Courses au supermarché");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM " + SCHEMA + "oauth_client WHERE id = ?",
                Boolean.class,
                CLIENT_ID)).isTrue();
    }

    @Test
    void customerLookupReturnsSyntheticCustomerAndRejectsUnknownCustomer() {
        CustomerSummary customer = customerLookupService.loadByExternalCustomerId("MOCK-CUST-001");

        assertThat(customer.externalCustomerId()).isEqualTo("MOCK-CUST-001");
        assertThat(customer.displayName()).isEqualTo("Amina Ben Romdhane");
        assertThat(customer.countryCode()).isEqualTo("TN");
        assertThat(customer.status()).isEqualTo("ACTIVE");
        assertThat(customer.createdAt()).isNotNull();

        assertThatThrownBy(() -> customerLookupService.loadByExternalCustomerId("UNKNOWN-CUSTOMER"))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void oauthClientLookupReturnsOnlyPublicMetadataAndValidatesCaller() {
        OAuthClientSummary client = oAuthClientLookupService.loadActiveClient(
                "sfwa-local-demo-client",
                "http://localhost:8080/mock-bank/callback");

        assertThat(client.clientIdentifier()).isEqualTo("sfwa-local-demo-client");
        assertThat(client.clientName()).isEqualTo("Smart Financial Wellness Demo Client");
        assertThat(client.redirectUri()).isEqualTo("http://localhost:8080/mock-bank/callback");
        assertThat(client.createdAt()).isNotNull();
        assertThat(Arrays.stream(OAuthClientSummary.class.getRecordComponents())
                .map(component -> component.getName())
                .toList())
                .containsExactly("clientIdentifier", "clientName", "redirectUri", "createdAt");

        assertThatThrownBy(() -> oAuthClientLookupService.loadActiveClient(
                "unknown-client",
                "http://localhost:8080/mock-bank/callback"))
                .isInstanceOf(ClientNotFoundException.class);
        assertThatThrownBy(() -> oAuthClientLookupService.loadActiveClient(
                "sfwa-local-demo-client",
                "http://localhost:8080/unregistered-callback"))
                .isInstanceOf(InvalidRedirectUriException.class);
    }

    @Test
    void oauthClientLookupRejectsInactiveClient() {
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "oauth_client SET enabled = FALSE WHERE id = ?",
                CLIENT_ID);
        try {
            assertThatThrownBy(() -> oAuthClientLookupService.loadActiveClient(
                    "sfwa-local-demo-client",
                    "http://localhost:8080/mock-bank/callback"))
                    .isInstanceOf(ClientInactiveException.class);
        } finally {
            jdbcTemplate.update(
                    "UPDATE " + SCHEMA + "oauth_client SET enabled = TRUE WHERE id = ?",
                    CLIENT_ID);
        }
    }

    @Test
    void accountLookupPreservesOwnershipOrderingTypesAndMaskedReferences() {
        List<AccountSummary> accounts = accountQueryService.listAccounts("MOCK-CUST-001");

        assertThat(accounts).extracting(AccountSummary::externalAccountId)
                .containsExactly("MOCK-ACC-001", "MOCK-ACC-002");
        assertThat(accounts).extracting(AccountSummary::accountType)
                .containsExactly("CHECKING", "SAVINGS");
        assertThat(accounts).extracting(AccountSummary::currency)
                .containsOnly("TND");
        assertThat(accounts).extracting(AccountSummary::maskedAccountReference)
                .containsExactly("****C001", "****C002");
        assertThat(accounts).extracting(AccountSummary::status)
                .containsOnly("ACTIVE");

        AccountSummary savings = accountQueryService.loadOwnedAccount("MOCK-CUST-001", "MOCK-ACC-002");
        assertThat(savings.displayName()).isEqualTo("Compte épargne");
        assertThat(savings.maskedAccountReference()).isEqualTo("****C002");
    }

    @Test
    void accountLookupRejectsUnknownAndForeignAccounts() {
        assertThatThrownBy(() -> accountQueryService.listAccounts("UNKNOWN-CUSTOMER"))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> accountQueryService.loadOwnedAccount("MOCK-CUST-001", "UNKNOWN-ACCOUNT"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> accountQueryService.loadOwnedAccount("MOCK-CUST-001", "MOCK-ACC-003"))
                .isInstanceOf(AccountNotOwnedException.class);
    }

    @Test
    void balanceLookupReturnsDecimalBalancesCurrenciesAndMaskedReferences() {
        BalanceSummary checking = balanceQueryService.loadBalance("MOCK-CUST-001", "MOCK-ACC-001");
        BalanceSummary savings = balanceQueryService.loadBalance("MOCK-CUST-001", "MOCK-ACC-002");

        assertThat(checking.currentBalance()).isEqualByComparingTo(new BigDecimal("3626.2500"));
        assertThat(checking.availableBalance()).isEqualByComparingTo(new BigDecimal("3626.2500"));
        assertThat(checking.currency()).isEqualTo("TND");
        assertThat(checking.maskedAccountReference()).isEqualTo("****C001");
        assertThat(savings.currentBalance()).isEqualByComparingTo(new BigDecimal("8420.3000"));
        assertThat(savings.availableBalance()).isEqualByComparingTo(new BigDecimal("8420.3000"));
        assertThat(savings.currency()).isEqualTo("TND");
        assertThat(savings.maskedAccountReference()).isEqualTo("****C002");

        assertThatThrownBy(() -> balanceQueryService.loadBalance("UNKNOWN-CUSTOMER", "MOCK-ACC-001"))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> balanceQueryService.loadBalance("MOCK-CUST-001", "UNKNOWN-ACCOUNT"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> balanceQueryService.loadBalance("MOCK-CUST-001", "MOCK-ACC-003"))
                .isInstanceOf(AccountNotOwnedException.class);
    }

    @Test
    void transactionLookupOrdersAndPaginatesDeterministically() {
        PaginatedTransactionResult firstPage = transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, null, 0, 2);
        PaginatedTransactionResult secondPage = transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, null, 1, 2);

        assertThat(firstPage.items()).extracting(transaction -> transaction.externalTransactionId())
                .containsExactly("MOCK-TXN-006", "MOCK-TXN-004");
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.first()).isTrue();
        assertThat(firstPage.last()).isFalse();
        assertThat(secondPage.items()).extracting(transaction -> transaction.externalTransactionId())
                .containsExactly("MOCK-TXN-003", "MOCK-TXN-002");
        assertThat(secondPage.first()).isFalse();
        assertThat(secondPage.last()).isFalse();
    }

    @Test
    void transactionLookupFiltersDatesAndRejectsInvalidRequests() {
        PaginatedTransactionResult filtered = transactionQueryService.loadTransactions(
                "MOCK-CUST-001",
                "MOCK-ACC-001",
                LocalDate.of(2026, 7, 26),
                LocalDate.of(2026, 7, 28),
                0,
                10);

        assertThat(filtered.items()).extracting(transaction -> transaction.externalTransactionId())
                .containsExactly("MOCK-TXN-004", "MOCK-TXN-003", "MOCK-TXN-002");
        assertThat(filtered.totalElements()).isEqualTo(3);

        PaginatedTransactionResult fromDate = transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", LocalDate.of(2026, 7, 28), null, 0, 10);
        PaginatedTransactionResult toDate = transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, LocalDate.of(2026, 7, 26), 0, 10);
        assertThat(fromDate.items()).extracting(transaction -> transaction.externalTransactionId())
                .containsExactly("MOCK-TXN-006", "MOCK-TXN-004");
        assertThat(toDate.items()).extracting(transaction -> transaction.externalTransactionId())
                .containsExactly("MOCK-TXN-002", "MOCK-TXN-001");

        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001",
                "MOCK-ACC-001",
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 7, 28),
                0,
                10))
                .isInstanceOf(InvalidTransactionDateRangeException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "UNKNOWN-CUSTOMER", "MOCK-ACC-001", null, null, 0, 10))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "UNKNOWN-ACCOUNT", null, null, 0, 10))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-003", null, null, 0, 10))
                .isInstanceOf(AccountNotOwnedException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, null, -1, 10))
                .isInstanceOf(InvalidTransactionPaginationException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, null, 0, 0))
                .isInstanceOf(InvalidTransactionPaginationException.class);
        assertThatThrownBy(() -> transactionQueryService.loadTransactions(
                "MOCK-CUST-001", "MOCK-ACC-001", null, null, 0, 101))
                .isInstanceOf(InvalidTransactionPaginationException.class);
    }
}
