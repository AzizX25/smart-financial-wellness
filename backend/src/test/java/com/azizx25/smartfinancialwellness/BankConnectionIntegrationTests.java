package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.azizx25.smartfinancialwellness.bank.BankAccessCredentials;
import com.azizx25.smartfinancialwellness.bank.MockBankAuthenticationPort;
import com.azizx25.smartfinancialwellness.bank.ReadOnlyBankDataPort;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidMockBankCredentialsException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankAuthenticationException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankUnavailableException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import({PostgresTestConfiguration.class, BankConnectionIntegrationTests.BankProviderTestConfiguration.class})
class BankConnectionIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final String BANK_PASSWORD = "DemoBank2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeMockBankProvider provider;

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @Test
    void connectionRequiresValidActiveApplicationJwt() throws Exception {
        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankCredentials(bankUsername(), BANK_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        AppRegistration pending = register(false);
        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(pending.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankCredentials(bankUsername(), BANK_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void successfulConnectionUsesCurrentUserAndNeverReturnsPasswordOrProviderToken() throws Exception {
        AppRegistration user = register(true);
        String username = bankUsername();

        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankCredentials(username, BANK_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerCode").value("MOCK_BANK"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reconnectRequired").value(false))
                .andExpect(jsonPath("$.connectedAt").isNotEmpty())
                .andExpect(jsonPath("$.tokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.providerAccessToken").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        UUID storedUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM " + SCHEMA + "bank_connection "
                        + "WHERE provider_code = 'MOCK_BANK' AND status = 'ACTIVE' AND user_id = ?",
                UUID.class,
                user.userId());
        String storedToken = jdbcTemplate.queryForObject(
                "SELECT a.provider_access_token FROM " + SCHEMA
                        + "bank_connection_authorization a JOIN " + SCHEMA
                        + "bank_connection c ON c.id = a.bank_connection_id "
                        + "WHERE c.user_id = ? AND a.status = 'ACTIVE'",
                String.class,
                user.userId());

        assertThat(storedUserId).isEqualTo(user.userId());
        assertThat(storedToken).startsWith("provider-token:");

        mockMvc.perform(get("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.providerAccessToken").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist());
    }

    @Test
    void invalidBankCredentialsReturnSafeUnprocessableResponse() throws Exception {
        AppRegistration user = register(true);
        String body = bankCredentials("invalid.demo", "private-wrong-password");

        MvcResult result = mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Invalid mock-bank credentials"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("private-wrong-password")
                .doesNotContain("provider-token");
    }

    @Test
    void connectedUserCanReadOwnedAccountsBalancesAndTransactions() throws Exception {
        AppRegistration user = register(true);
        connect(user, bankUsername());

        MvcResult accountsResult = mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].displayName").value("Compte courant démo"))
                .andExpect(jsonPath("$[0].maskedAccountReference").value("****0001"))
                .andExpect(jsonPath("$[0].externalAccountId").doesNotExist())
                .andExpect(jsonPath("$[0].currentBalance").doesNotExist())
                .andReturn();
        UUID accountId = UUID.fromString(objectMapper
                .readTree(accountsResult.getResponse().getContentAsString())
                .get(0)
                .get("id")
                .asText());

        mockMvc.perform(get("/api/bank/accounts/{accountId}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.externalAccountId").doesNotExist());

        mockMvc.perform(get("/api/bank/accounts/{accountId}/balance", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.currentBalance").value(3626.25))
                .andExpect(jsonPath("$.availableBalance").value(3600.10));

        mockMvc.perform(get("/api/bank/accounts/{accountId}/transactions", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value(86.45))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].bookingDate").value("2026-07-26"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void noConnectionAndCrossUserAccountAccessAreRejected() throws Exception {
        AppRegistration unconnected = register(true);
        mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(unconnected.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A mock-bank connection is required"));

        AppRegistration first = register(true);
        AppRegistration second = register(true);
        connect(first, bankUsername());
        connect(second, bankUsername());
        UUID secondAccountId = firstAccountId(second);

        mockMvc.perform(get("/api/bank/accounts/{accountId}/balance", secondAccountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bank account not found"));
    }

    @Test
    void providerCustomerCannotBeLinkedToTwoApplicationUsers() throws Exception {
        AppRegistration first = register(true);
        AppRegistration second = register(true);
        String sharedUsername = bankUsername();
        connect(first, sharedUsername);

        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(second.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankCredentials(sharedUsername, BANK_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This mock-bank customer is already linked to another user"));
    }

    @Test
    void disconnectClearsProviderIdentityAndTokenAndDisablesAccounts() throws Exception {
        AppRegistration user = register(true);
        connect(user, bankUsername());
        UUID accountId = firstAccountId(user);

        mockMvc.perform(delete("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());

        ConnectionState state = jdbcTemplate.queryForObject(
                "SELECT c.status, c.external_customer_id, c.disconnected_at IS NOT NULL, "
                        + "a.provider_access_token FROM " + SCHEMA
                        + "bank_connection c JOIN " + SCHEMA
                        + "bank_connection_authorization a "
                        + "ON a.bank_connection_id = c.id "
                        + "WHERE c.user_id = ? ORDER BY a.created_at DESC LIMIT 1",
                (resultSet, rowNumber) -> new ConnectionState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3),
                        resultSet.getString(4)),
                user.userId());
        assertThat(state.status()).isEqualTo("DISCONNECTED");
        assertThat(state.customerId()).isNull();
        assertThat(state.disconnectedAtPresent()).isTrue();
        assertThat(state.providerToken()).isNull();

        mockMvc.perform(get("/api/bank/accounts/{accountId}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A mock-bank connection is required"));
    }

    @Test
    void expiredOrRejectedProviderTokenRequiresReconnectionAndIsClearedOnRejection() throws Exception {
        AppRegistration expired = register(true);
        connect(expired, bankUsername());
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "bank_connection_authorization SET status = 'EXPIRED', "
                        + "provider_access_token = NULL, secret_reference = NULL "
                        + "WHERE bank_connection_id = (SELECT id FROM " + SCHEMA
                        + "bank_connection WHERE user_id = ? AND status = 'ACTIVE')",
                expired.userId());
        mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(expired.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Mock-bank reconnection is required"));

        AppRegistration rejected = register(true);
        connect(rejected, bankUsername());
        provider.rejectProviderToken = true;
        mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(rejected.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Mock-bank reconnection is required"));

        String connectionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM " + SCHEMA + "bank_connection WHERE user_id = ?",
                String.class,
                rejected.userId());
        String storedToken = jdbcTemplate.queryForObject(
                "SELECT provider_access_token FROM " + SCHEMA
                        + "bank_connection_authorization WHERE bank_connection_id = (SELECT id FROM "
                        + SCHEMA + "bank_connection WHERE user_id = ?)",
                String.class,
                rejected.userId());
        assertThat(connectionStatus).isEqualTo("ERROR");
        assertThat(storedToken).isNull();

        mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(rejected.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Mock-bank reconnection is required"));
    }

    @Test
    void providerAvailabilityFailureDoesNotInvalidateConnection() throws Exception {
        AppRegistration user = register(true);
        connect(user, bankUsername());
        provider.unavailable = true;

        mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Mock-bank service is unavailable"));

        ConnectionState state = jdbcTemplate.queryForObject(
                "SELECT c.status, c.external_customer_id, "
                        + "c.disconnected_at IS NOT NULL, a.provider_access_token "
                        + "FROM " + SCHEMA + "bank_connection c JOIN " + SCHEMA
                        + "bank_connection_authorization a "
                        + "ON a.bank_connection_id = c.id "
                        + "WHERE c.user_id = ?",
                (resultSet, rowNumber) -> new ConnectionState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3),
                        resultSet.getString(4)),
                user.userId());
        assertThat(state.status()).isEqualTo("ACTIVE");
        assertThat(state.customerId()).isNotBlank();
        assertThat(state.providerToken()).isNotBlank();
    }

    @Test
    void invalidFiltersAndMalformedAccountIdsReturnControlledErrors() throws Exception {
        AppRegistration user = register(true);
        connect(user, bankUsername());
        UUID accountId = firstAccountId(user);

        mockMvc.perform(get("/api/bank/accounts/{accountId}/transactions", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"));
        mockMvc.perform(get("/api/bank/accounts/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for request parameter 'accountId'"));
    }

    private AppRegistration register(boolean activate) throws Exception {
        String email = "bank-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s",
                                  "password":"StrongPass123!",
                                  "firstName":"Bank",
                                  "lastName":"Tester",
                                  "displayName":"Bank Tester"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        if (activate) {
            jdbcTemplate.update(
                    "UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?",
                    userId);
            jdbcTemplate.update(
                    "UPDATE " + SCHEMA + "authentication_identity SET status = 'ACTIVE', "
                            + "verified_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                    userId);
        }
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private void connect(AppRegistration user, String username) throws Exception {
        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankCredentials(username, BANK_PASSWORD)))
                .andExpect(status().isOk());
    }

    private UUID firstAccountId(AppRegistration user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/bank/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0)
                .get("id")
                .asText());
    }

    private static String bankUsername() {
        return "demo-" + UUID.randomUUID();
    }

    private static String bankCredentials(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppRegistration(UUID userId, String accessToken) {
    }

    private record ConnectionState(
            String status,
            String customerId,
            boolean disconnectedAtPresent,
            String providerToken) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BankProviderTestConfiguration {

        @Bean
        @Primary
        FakeMockBankProvider fakeMockBankProvider() {
            return new FakeMockBankProvider();
        }
    }

    static final class FakeMockBankProvider
            implements MockBankAuthenticationPort, ReadOnlyBankDataPort {

        private boolean unavailable;
        private boolean rejectProviderToken;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private List<ExternalBankTransaction> primaryTransactions;
        private volatile CountDownLatch listEntered;
        private volatile CountDownLatch releaseList;

        void reset() {
            unavailable = false;
            rejectProviderToken = false;
            currentBalance = new BigDecimal("3626.2500");
            availableBalance = new BigDecimal("3600.1000");
            primaryTransactions = List.of(defaultTransaction());
            listEntered = null;
            releaseList = null;
        }

        void changeBalance(String current, String available) {
            currentBalance = new BigDecimal(current);
            availableBalance = new BigDecimal(available);
        }

        void addPrimaryTransaction(ExternalBankTransaction transaction) {
            primaryTransactions = java.util.stream.Stream.concat(
                    primaryTransactions.stream(), java.util.stream.Stream.of(transaction)).toList();
        }

        void replacePrimaryTransaction(ExternalBankTransaction transaction) {
            primaryTransactions = List.of(transaction);
        }

        void setUnavailable(boolean value) {
            unavailable = value;
        }

        void setRejectProviderToken(boolean value) {
            rejectProviderToken = value;
        }

        void blockNextAccountList() {
            listEntered = new CountDownLatch(1);
            releaseList = new CountDownLatch(1);
        }

        boolean awaitAccountListBlocked() throws InterruptedException {
            return listEntered != null && listEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseAccountList() {
            if (releaseList != null) {
                releaseList.countDown();
            }
        }

        @Override
        public BankLoginResult login(String username, String password) {
            if (unavailable) {
                throw new MockBankUnavailableException();
            }
            if ("invalid.demo".equals(username) || !BANK_PASSWORD.equals(password)) {
                throw new InvalidMockBankCredentialsException();
            }
            String customerId = "MOCK-CUST-" + username;
            return new BankLoginResult(
                    customerId,
                    "provider-token:" + customerId,
                    Instant.now().plusSeconds(600));
        }

        @Override
        public List<ExternalBankAccount> listAccounts(BankAccessCredentials credentials) {
            validateAccess(credentials);
            CountDownLatch entered = listEntered;
            CountDownLatch release = releaseList;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the fake provider");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fake provider wait was interrupted", exception);
                } finally {
                    listEntered = null;
                    releaseList = null;
                }
            }
            return List.of(
                    account(credentials.customerId(), "001", "Compte courant démo", "CHECKING", "****0001"),
                    account(credentials.customerId(), "002", "Compte épargne démo", "SAVINGS", "****0002"));
        }

        @Override
        public ExternalBankAccount getAccount(BankAccessCredentials credentials, String accountId) {
            validateAccess(credentials);
            return listAccounts(credentials).stream()
                    .filter(account -> account.externalAccountId().equals(accountId))
                    .findFirst()
                    .orElseThrow(() -> new MockBankNotFoundException("Account not found"));
        }

        @Override
        public ExternalBankBalance getBalance(BankAccessCredentials credentials, String accountId) {
            ExternalBankAccount account = getAccount(credentials, accountId);
            return new ExternalBankBalance(
                    account.externalAccountId(),
                    "TND",
                    currentBalance,
                    availableBalance,
                    "ACTIVE",
                    account.maskedAccountReference());
        }

        @Override
        public ExternalBankTransactionPage listTransactions(
                BankAccessCredentials credentials,
                String accountId,
                LocalDate from,
                LocalDate to,
                int page,
                int size) {
            getAccount(credentials, accountId);
            List<ExternalBankTransaction> allTransactions = accountId.endsWith("-001")
                    ? primaryTransactions
                    : List.of();
            int fromIndex = Math.min(page * size, allTransactions.size());
            int toIndex = Math.min(fromIndex + size, allTransactions.size());
            int totalPages = allTransactions.isEmpty()
                    ? 0
                    : (int) Math.ceil((double) allTransactions.size() / size);
            return new ExternalBankTransactionPage(
                    allTransactions.subList(fromIndex, toIndex),
                    page,
                    size,
                    allTransactions.size(),
                    totalPages,
                    page == 0,
                    totalPages == 0 || page == totalPages - 1);
        }

        private void validateAccess(BankAccessCredentials credentials) {
            if (unavailable) {
                throw new MockBankUnavailableException();
            }
            if (rejectProviderToken) {
                throw new MockBankAuthenticationException();
            }
            if (!("provider-token:" + credentials.customerId()).equals(credentials.accessToken())) {
                throw new MockBankAuthenticationException();
            }
        }

        private ExternalBankAccount account(
                String customerId,
                String suffix,
                String name,
                String type,
                String masked) {
            return new ExternalBankAccount(
                    customerId + "-ACC-" + suffix,
                    name,
                    type,
                    "TND",
                    masked,
                    "ACTIVE",
                    OffsetDateTime.of(2026, 7, 1, 8, 0, 0, 0, ZoneOffset.UTC));
        }

        private ExternalBankTransaction defaultTransaction() {
            return new ExternalBankTransaction(
                    "MOCK-TXN-001",
                    LocalDate.of(2026, 7, 26),
                    LocalDate.of(2026, 7, 26),
                    new BigDecimal("86.4500"),
                    "DEBIT",
                    "TND",
                    "Courses au supermarché",
                    "Monoprix",
                    "POSTED");
        }
    }
}
