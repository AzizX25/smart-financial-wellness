package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
        PostgresTestConfiguration.class,
        BankConnectionIntegrationTests.BankProviderTestConfiguration.class
})
class BankSynchronizationIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final String BANK_PASSWORD = "DemoBank2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BankConnectionIntegrationTests.FakeMockBankProvider provider;

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @Test
    void initialSynchronizationPersistsNormalizedDataAndReturnsOnlySafeFields() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);

        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.accountsCreated").value(2))
                .andExpect(jsonPath("$.accountsUpdated").value(0))
                .andExpect(jsonPath("$.transactionsCreated").value(1))
                .andExpect(jsonPath("$.transactionsUpdated").value(0))
                .andExpect(jsonPath("$.lastSuccessfulAt").isNotEmpty());

        Integer accounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA
                        + "financial_account WHERE user_id = ? AND first_synchronized_at IS NOT NULL",
                Integer.class,
                user.userId());
        Integer transactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA
                        + "transaction_record WHERE user_id = ? AND source_type = 'BANK_SYNC'",
                Integer.class,
                user.userId());
        assertThat(accounts).isEqualTo(2);
        assertThat(transactions).isEqualTo(1);

        MvcResult accountResult = mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].provider").value("MOCK_BANK"))
                .andExpect(jsonPath("$[0].maskedAccountReference").value("****0001"))
                .andExpect(jsonPath("$[0].currentBalance").value(3626.25))
                .andExpect(jsonPath("$[0].availableBalance").value(3600.10))
                .andExpect(jsonPath("$[0].currency").value("TND"))
                .andExpect(jsonPath("$[0].providerStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[0].externalAccountId").doesNotExist())
                .andExpect(jsonPath("$[0].providerAccessToken").doesNotExist())
                .andExpect(jsonPath("$[0].secretReference").doesNotExist())
                .andReturn();
        UUID accountId = UUID.fromString(objectMapper.readTree(
                accountResult.getResponse().getContentAsString()).get(0).get("id").asText());

        MvcResult transactionsResult = mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].amount").value(86.45))
                .andExpect(jsonPath("$.items[0].currency").value("TND"))
                .andExpect(jsonPath("$.items[0].description").value("Courses au supermarché"))
                .andExpect(jsonPath("$.items[0].externalTransactionId").doesNotExist())
                .andExpect(jsonPath("$.items[0].syncJobId").doesNotExist())
                .andReturn();
        assertThat(transactionsResult.getResponse().getContentAsString())
                .doesNotContain("provider-token")
                .doesNotContain("password")
                .doesNotContain("secretReference");

        mockMvc.perform(get("/api/bank/synchronizations/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.lastSuccessfulAt").isNotEmpty())
                .andExpect(jsonPath("$.latestSynchronization.status").value("SUCCEEDED"));
    }

    @Test
    void repeatedSynchronizationIsIdempotentAndAppliesSafeProviderChanges() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);
        synchronize(user);

        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountsCreated").value(0))
                .andExpect(jsonPath("$.accountsUpdated").value(2))
                .andExpect(jsonPath("$.transactionsCreated").value(0))
                .andExpect(jsonPath("$.transactionsUpdated").value(1));
        assertCounts(user, 2, 1);

        provider.changeBalance("3700.7500", "3680.2500");
        provider.replacePrimaryTransaction(new ExternalBankTransaction(
                "MOCK-TXN-001",
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 7, 27),
                new BigDecimal("90.2500"),
                "DEBIT",
                "TND",
                "Courses corrigées",
                "Marché local",
                "POSTED"));
        provider.addPrimaryTransaction(transaction("MOCK-TXN-NEW", "Nouvelle opération", "15.5000"));

        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountsCreated").value(0))
                .andExpect(jsonPath("$.accountsUpdated").value(2))
                .andExpect(jsonPath("$.transactionsCreated").value(1))
                .andExpect(jsonPath("$.transactionsUpdated").value(1));
        assertCounts(user, 2, 2);

        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT current_balance FROM " + SCHEMA
                        + "financial_account WHERE user_id = ? AND masked_identifier = '****0001'",
                BigDecimal.class,
                user.userId());
        String updatedTransaction = jdbcTemplate.queryForObject(
                "SELECT raw_description || '|' || amount::text || '|' || direction FROM "
                        + SCHEMA + "transaction_record WHERE user_id = ? AND external_transaction_id = 'MOCK-TXN-001'",
                String.class,
                user.userId());
        assertThat(balance).isEqualByComparingTo("3700.7500");
        assertThat(updatedTransaction).isEqualTo("Courses corrigées|90.2500|DEBIT");

        synchronize(user);
        assertCounts(user, 2, 2);
    }

    @Test
    void localEndpointsAreAuthenticatedAndStrictlyUserScoped() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/bank/synchronizations"))
                .andExpect(status().isUnauthorized());

        AppRegistration first = registerAndActivate();
        AppRegistration second = registerAndActivate();
        connect(first);
        connect(second);
        synchronize(first);
        synchronize(second);
        UUID secondAccountId = firstLocalAccountId(second);

        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.accessToken()))
                        .param("accountId", secondAccountId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bank account not found"));
    }

    @Test
    void synchronizationRequiresConnectionButHistorySurvivesDisconnection() throws Exception {
        AppRegistration unconnected = registerAndActivate();
        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(unconnected.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A mock-bank connection is required"));

        AppRegistration user = registerAndActivate();
        String bankUsername = "sync-reconnect-" + UUID.randomUUID();
        connect(user, bankUsername);
        synchronize(user);
        UUID accountIdBeforeDisconnect = firstLocalAccountId(user);
        mockMvc.perform(delete("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A mock-bank connection is required"));
        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].localStatus").value("DISCONNECTED"));
        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        connect(user, bankUsername);
        synchronize(user);
        assertCounts(user, 2, 1);
        assertThat(firstLocalAccountId(user)).isEqualTo(accountIdBeforeDisconnect);
    }

    @Test
    void providerFailuresPreserveHistoryAndDoNotInvalidateApplicationSession() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);
        synchronize(user);
        Instant lastSuccess = jdbcTemplate.queryForObject(
                "SELECT last_synced_at FROM " + SCHEMA + "bank_connection WHERE user_id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                user.userId());

        provider.setUnavailable(true);
        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Mock-bank service is unavailable"));
        assertFailureState(user, "BANK_PROVIDER_UNAVAILABLE", lastSuccess);
        assertCounts(user, 2, 1);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());

        provider.setUnavailable(false);
        provider.setRejectProviderToken(true);
        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("BANK_AUTHORIZATION_EXPIRED"));
        assertFailureState(user, "BANK_AUTHORIZATION_EXPIRED", lastSuccess);
        assertCounts(user, 2, 1);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void overlappingSynchronizationIsRejectedAndCannotCreateDuplicates() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);
        provider.blockNextAccountList();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(
                            post("/api/bank/synchronizations")
                                    .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                    .andReturn());
            assertThat(provider.awaitAccountListBlocked()).isTrue();

            mockMvc.perform(post("/api/bank/synchronizations")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("BANK_SYNC_IN_PROGRESS"));

            provider.releaseAccountList();
            assertThat(first.get().getResponse().getStatus()).isEqualTo(200);
        } finally {
            provider.releaseAccountList();
            executor.shutdownNow();
        }
        assertCounts(user, 2, 1);
    }

    @Test
    void synchronizationReadsEveryProviderTransactionPage() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);
        for (int index = 0; index < 105; index++) {
            provider.addPrimaryTransaction(transaction(
                    "MOCK-PAGED-" + index,
                    "Opération paginée " + index,
                    "1.0000"));
        }

        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsCreated").value(106));
        assertCounts(user, 2, 106);
    }

    @Test
    void dashboardSummaryIsUserScopedCurrencyAwareAndUsesDecimalAggregates() throws Exception {
        AppRegistration first = registerAndActivate();
        AppRegistration second = registerAndActivate();
        connect(first);
        connect(second);
        synchronize(first);
        synchronize(second);

        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_account SET currency = 'EUR', "
                        + "current_balance = 100.1200 WHERE user_id = ? AND masked_identifier = '****0002'",
                second.userId());

        mockMvc.perform(get("/api/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.accessToken()))
                        .param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-07"))
                .andExpect(jsonPath("$.balanceTotals.length()").value(1))
                .andExpect(jsonPath("$.balanceTotals[0].currency").value("TND"))
                .andExpect(jsonPath("$.balanceTotals[0].totalBalance").value(7252.5))
                .andExpect(jsonPath("$.balanceTotals[0].accountCount").value(2))
                .andExpect(jsonPath("$.monthlyCashFlows[0].income").value(0))
                .andExpect(jsonPath("$.monthlyCashFlows[0].expenses").value(86.45))
                .andExpect(jsonPath("$.monthlyCashFlows[0].netCashFlow").value(-86.45));

        mockMvc.perform(get("/api/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(second.accessToken()))
                        .param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceTotals.length()").value(2))
                .andExpect(jsonPath("$.balanceTotals[0].currency").value("EUR"))
                .andExpect(jsonPath("$.balanceTotals[0].totalBalance").value(100.12))
                .andExpect(jsonPath("$.balanceTotals[1].currency").value("TND"))
                .andExpect(jsonPath("$.balanceTotals[1].totalBalance").value(3626.25));
    }

    @Test
    void dashboardSummaryReturnsEmptyCollectionsAndTransactionsSupportSafeSearch() throws Exception {
        AppRegistration emptyUser = registerAndActivate();
        mockMvc.perform(get("/api/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(emptyUser.accessToken()))
                        .param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceTotals.length()").value(0))
                .andExpect(jsonPath("$.monthlyCashFlows.length()").value(0));

        AppRegistration synchronizedUser = registerAndActivate();
        connect(synchronizedUser);
        synchronize(synchronizedUser);
        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(synchronizedUser.accessToken()))
                        .param("search", "monoprix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Courses au supermarché"));
        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(synchronizedUser.accessToken()))
                        .param("search", "inconnue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        UUID checkingAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA
                        + "financial_account WHERE user_id = ? AND masked_identifier = '****0001'",
                UUID.class,
                synchronizedUser.userId());
        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(synchronizedUser.accessToken()))
                        .param("accountId", checkingAccountId.toString())
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("direction", "DEBIT")
                        .param("search", "monoprix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].accountId")
                        .value(checkingAccountId.toString()))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"));
    }

    @Test
    void dashboardSummaryDoesNotClassifyRecognizedInternalTransfers() throws Exception {
        AppRegistration user = registerAndActivate();
        connect(user);
        synchronize(user);
        UUID incomingTransactionId = jdbcTemplate.queryForObject(
                """
                WITH source AS (
                    SELECT transaction.*, account.bank_connection_id
                      FROM smart_financial_wellness.transaction_record transaction
                      JOIN smart_financial_wellness.financial_account account
                        ON account.id = transaction.financial_account_id
                     WHERE transaction.user_id = ?
                     LIMIT 1
                ), target_account AS (
                    SELECT id
                      FROM smart_financial_wellness.financial_account
                     WHERE user_id = ? AND masked_identifier = '****0002'
                )
                INSERT INTO smart_financial_wellness.transaction_record (
                    user_id, financial_account_id, category_id, booking_date, value_date,
                    raw_description, normalized_description, amount, currency, direction,
                    source_type, sync_job_id, external_transaction_id, category_source,
                    provider_status, first_imported_at, last_synchronized_at
                )
                SELECT source.user_id, target_account.id,
                       '00000000-0000-0000-0000-000000000001'::UUID,
                       source.booking_date, source.value_date, 'Virement interne entrant',
                       'virement interne entrant', source.amount, source.currency, 'CREDIT',
                       'BANK_SYNC', source.sync_job_id, 'MOCK-INTERNAL-IN', 'UNCATEGORIZED',
                       'POSTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM source CROSS JOIN target_account
                RETURNING id
                """,
                UUID.class,
                user.userId(),
                user.userId());
        UUID outgoingTransactionId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA
                        + "transaction_record WHERE user_id = ? AND direction = 'DEBIT'",
                UUID.class,
                user.userId());
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(outgoing_transaction_id, incoming_transaction_id, detection_method) "
                        + "VALUES (?, ?, 'USER')",
                outgoingTransactionId,
                incomingTransactionId);

        mockMvc.perform(get("/api/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .param("month", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyCashFlows.length()").value(0));
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "sync-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s",
                                  "password":"StrongPass123!",
                                  "firstName":"Sync",
                                  "lastName":"Tester",
                                  "displayName":"Sync Tester"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "authentication_identity SET status = 'ACTIVE', "
                        + "verified_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private void connect(AppRegistration user) throws Exception {
        connect(user, "sync-demo-" + UUID.randomUUID());
    }

    private void connect(AppRegistration user, String username) throws Exception {
        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, BANK_PASSWORD)))
                .andExpect(status().isOk());
    }

    private void synchronize(AppRegistration user) throws Exception {
        mockMvc.perform(post("/api/bank/synchronizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());
    }

    private UUID firstLocalAccountId(AppRegistration user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asText());
    }

    private void assertCounts(AppRegistration user, int accounts, int transactions) {
        Integer accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA
                        + "financial_account WHERE user_id = ? AND first_synchronized_at IS NOT NULL",
                Integer.class,
                user.userId());
        Integer transactionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "transaction_record WHERE user_id = ?",
                Integer.class,
                user.userId());
        assertThat(accountCount).isEqualTo(accounts);
        assertThat(transactionCount).isEqualTo(transactions);
    }

    private void assertFailureState(
            AppRegistration user,
            String expectedFailureCode,
            Instant expectedLastSuccess) {
        FailureState state = jdbcTemplate.queryForObject(
                "SELECT sync_status, last_sync_failure_code, last_synced_at FROM "
                        + SCHEMA + "bank_connection WHERE user_id = ?",
                (resultSet, rowNumber) -> new FailureState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getTimestamp(3).toInstant()),
                user.userId());
        assertThat(state.status()).isEqualTo("FAILED");
        assertThat(state.failureCode()).isEqualTo(expectedFailureCode);
        assertThat(state.lastSuccess()).isEqualTo(expectedLastSuccess);
    }

    private ExternalBankTransaction transaction(String id, String description, String amount) {
        return new ExternalBankTransaction(
                id,
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 4),
                new BigDecimal(amount),
                "CREDIT",
                "TND",
                description,
                "Validation locale",
                "POSTED");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppRegistration(UUID userId, String accessToken) {
    }

    private record FailureState(String status, String failureCode, Instant lastSuccess) {
    }
}
