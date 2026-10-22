package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.math.BigDecimal;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.azizx25.smartfinancialwellness.bank.BankAccessCredentials;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidMockBankCredentialsException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankAuthenticationException;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidBankRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankRejectedRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MockBankHttpClientTests {

    private static final String BASE_URL = "https://mock-bank.test";
    private static final String CUSTOMER_ID = "MOCK-CUST-001";
    private static final String ACCOUNT_ID = "MOCK-ACC-001";
    private static final String ACCESS_TOKEN = "provider-access-token";
    private static final BankAccessCredentials ACCESS =
            new BankAccessCredentials(CUSTOMER_ID, ACCESS_TOKEN);
    private static final String ACCOUNTS_PATH =
            "/api/v1/customers/" + CUSTOMER_ID + "/accounts";
    private static final String ACCOUNT_PATH = ACCOUNTS_PATH + "/" + ACCOUNT_ID;
    private static final String TRANSACTIONS_PATH = ACCOUNT_PATH + "/transactions";

    private MockRestServiceServer server;
    private MockBankHttpClient client;

    @BeforeEach
    void setUp() {
        JsonMapper applicationMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        JsonMapper tolerantMapper = MockBankClientConfiguration.tolerantJsonMapper(applicationMapper);
        var properties = new MockBankClientProperties(
                URI.create(BASE_URL),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        RestClient.Builder builder = MockBankClientConfiguration.configureBuilder(
                RestClient.builder(),
                properties,
                tolerantMapper);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MockBankHttpClient(builder.build(), tolerantMapper);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void listsAccountsWithExactRequestAndMapsUnknownFieldsSafely() {
        server.expect(requestTo(BASE_URL + ACCOUNTS_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("""
                        [{
                          "externalAccountId":"MOCK-ACC-001",
                          "displayName":"Everyday account",
                          "accountType":"CHECKING",
                          "currency":"TND",
                          "maskedAccountReference":"****0001",
                          "status":"ACTIVE",
                          "createdAt":"2026-01-10T09:30:00Z",
                          "futureField":"ignored"
                        }]
                        """, MediaType.APPLICATION_JSON));

        var accounts = client.listAccounts(ACCESS);

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.externalAccountId()).isEqualTo("MOCK-ACC-001");
            assertThat(account.displayName()).isEqualTo("Everyday account");
            assertThat(account.accountType()).isEqualTo("CHECKING");
            assertThat(account.currency()).isEqualTo("TND");
            assertThat(account.maskedAccountReference()).isEqualTo("****0001");
            assertThat(account.status()).isEqualTo("ACTIVE");
            assertThat(account.createdAt()).isEqualTo(OffsetDateTime.parse("2026-01-10T09:30:00Z"));
        });
    }

    @Test
    void getsSingleAccountWithExactRequestAndMapping() {
        server.expect(requestTo(BASE_URL + ACCOUNT_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(accountJson(), MediaType.APPLICATION_JSON));

        var account = client.getAccount(ACCESS, ACCOUNT_ID);

        assertThat(account.externalAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(account.displayName()).isEqualTo("Everyday account");
        assertThat(account.accountType()).isEqualTo("CHECKING");
        assertThat(account.currency()).isEqualTo("TND");
        assertThat(account.maskedAccountReference()).isEqualTo("****0001");
        assertThat(account.status()).isEqualTo("ACTIVE");
        assertThat(account.createdAt()).isEqualTo(OffsetDateTime.parse("2026-01-10T09:30:00Z"));
    }

    @Test
    void getsBalanceAndPreservesBigDecimalPrecisionAndScale() {
        server.expect(requestTo(BASE_URL + ACCOUNT_PATH + "/balance"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "externalAccountId":"MOCK-ACC-001",
                          "currency":"TND",
                          "currentBalance":123456789012345.6789,
                          "availableBalance":0.0100,
                          "status":"ACTIVE",
                          "maskedAccountReference":"****0001"
                        }
                        """, MediaType.APPLICATION_JSON));

        var balance = client.getBalance(ACCESS, ACCOUNT_ID);

        assertThat(balance.externalAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(balance.currency()).isEqualTo("TND");
        assertThat(balance.currentBalance()).isEqualTo(new BigDecimal("123456789012345.6789"));
        assertThat(balance.availableBalance()).isEqualTo(new BigDecimal("0.0100"));
        assertThat(balance.status()).isEqualTo("ACTIVE");
        assertThat(balance.maskedAccountReference()).isEqualTo("****0001");
    }

    @Test
    void requestsDefaultTransactionPageExplicitlyAndMapsCompleteEnvelope() {
        server.expect(requestTo(BASE_URL + TRANSACTIONS_PATH + "?page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(transactionPageJson(), MediaType.APPLICATION_JSON));

        var result = client.listTransactions(ACCESS, ACCOUNT_ID, null, null, 0, 20);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        assertThat(result.items()).singleElement().satisfies(transaction -> {
            assertThat(transaction.externalTransactionId()).isEqualTo("MOCK-TXN-001");
            assertThat(transaction.bookingDate()).isEqualTo(LocalDate.of(2026, 6, 14));
            assertThat(transaction.valueDate()).isEqualTo(LocalDate.of(2026, 6, 15));
            assertThat(transaction.amount()).isEqualTo(new BigDecimal("1250.3400"));
            assertThat(transaction.direction()).isEqualTo("CREDIT");
            assertThat(transaction.currency()).isEqualTo("TND");
            assertThat(transaction.description()).isEqualTo("Salary payment");
            assertThat(transaction.counterpartyName()).isEqualTo("Example employer");
            assertThat(transaction.status()).isEqualTo("POSTED");
        });
    }

    @Test
    void forwardsBothInclusiveDateFilters() {
        server.expect(requestTo(BASE_URL + TRANSACTIONS_PATH
                        + "?from=2026-05-01&to=2026-05-31&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(0, 20), MediaType.APPLICATION_JSON));

        client.listTransactions(
                ACCESS,
                ACCOUNT_ID,
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 31),
                0,
                20);
    }

    @Test
    void forwardsOnlyFromDateWhenToDateIsAbsent() {
        server.expect(requestTo(BASE_URL + TRANSACTIONS_PATH + "?from=2026-05-01&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(0, 20), MediaType.APPLICATION_JSON));

        client.listTransactions(
                ACCESS,
                ACCOUNT_ID,
                LocalDate.of(2026, 5, 1),
                null,
                0,
                20);
    }

    @Test
    void forwardsOnlyToDateWhenFromDateIsAbsent() {
        server.expect(requestTo(BASE_URL + TRANSACTIONS_PATH + "?to=2026-05-31&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(0, 20), MediaType.APPLICATION_JSON));

        client.listTransactions(
                ACCESS,
                ACCOUNT_ID,
                null,
                LocalDate.of(2026, 5, 31),
                0,
                20);
    }

    @Test
    void forwardsPaginationAndMapsEmptyPage() {
        server.expect(requestTo(BASE_URL + TRANSACTIONS_PATH + "?page=3&size=7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(3, 7), MediaType.APPLICATION_JSON));

        var result = client.listTransactions(ACCESS, ACCOUNT_ID, null, null, 3, 7);

        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isTrue();
    }

    @Test
    void encodesIdentifiersAsIndividualPathSegments() {
        server.expect(request -> assertThat(request.getURI().getRawPath()).isEqualTo(
                        "/api/v1/customers/customer%20one%2Ftwo/accounts/account%20three%2Ffour"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(accountJson(), MediaType.APPLICATION_JSON));

        client.getAccount(
                new BankAccessCredentials("customer one/two", ACCESS_TOKEN),
                "account three/four");
    }

    @Test
    void rejectsBlankCustomerIdWithoutSendingRequest() {
        assertThatThrownBy(() -> client.listAccounts(new BankAccessCredentials(" \t", ACCESS_TOKEN)))
                .isInstanceOf(InvalidBankRequestException.class)
                .hasMessage("customerId must not be blank");
    }

    @Test
    void rejectsBlankAccountIdWithoutSendingRequest() {
        assertThatThrownBy(() -> client.getBalance(ACCESS, ""))
                .isInstanceOf(InvalidBankRequestException.class)
                .hasMessage("accountId must not be blank");
    }

    @Test
    void rejectsNegativePageWithoutSendingRequest() {
        assertThatThrownBy(() -> client.listTransactions(
                ACCESS, ACCOUNT_ID, null, null, -1, 20))
                .isInstanceOf(InvalidBankRequestException.class)
                .hasMessage("page must be greater than or equal to 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsOutOfRangePageSizeWithoutSendingRequest(int size) {
        assertThatThrownBy(() -> client.listTransactions(
                ACCESS, ACCOUNT_ID, null, null, 0, size))
                .isInstanceOf(InvalidBankRequestException.class)
                .hasMessage("size must be between 1 and 100");
    }

    @Test
    void rejectsReversedDatesWithoutSendingRequest() {
        assertThatThrownBy(() -> client.listTransactions(
                ACCESS,
                ACCOUNT_ID,
                LocalDate.of(2026, 6, 2),
                LocalDate.of(2026, 6, 1),
                0,
                20))
                .isInstanceOf(InvalidBankRequestException.class)
                .hasMessage("from must not be after to");
    }

    @Test
    void preservesSafeCustomerNotFoundMessage() {
        expectError(ACCOUNTS_PATH, HttpStatus.NOT_FOUND, """
                {"status":404,"error":"Not Found","message":"Customer not found","path":"ignored"}
                """);

        assertThatThrownBy(() -> client.listAccounts(ACCESS))
                .isInstanceOf(MockBankNotFoundException.class)
                .hasMessage("Customer not found");
    }

    @Test
    void preservesSafeAccountNotFoundMessage() {
        expectError(ACCOUNT_PATH, HttpStatus.NOT_FOUND, """
                {"status":404,"error":"Not Found","message":"Account not found","path":"ignored"}
                """);

        assertThatThrownBy(() -> client.getAccount(ACCESS, ACCOUNT_ID))
                .isInstanceOf(MockBankNotFoundException.class)
                .hasMessage("Account not found");
    }

    @Test
    void replacesUnsafeNotFoundBodyWithFixedMessage() {
        expectError(ACCOUNT_PATH, HttpStatus.NOT_FOUND, """
                {"message":"private-account-marker","debug":"raw-database-details"}
                """);

        assertThatThrownBy(() -> client.getAccount(ACCESS, ACCOUNT_ID))
                .isInstanceOf(MockBankNotFoundException.class)
                .hasMessage("Mock-bank resource not found")
                .hasMessageNotContaining("private-account-marker")
                .hasMessageNotContaining("raw-database-details");
    }

    @Test
    void mapsUpstreamBadRequestWithoutLeakingItsBody() {
        expectError(TRANSACTIONS_PATH + "?page=0&size=20", HttpStatus.BAD_REQUEST, """
                {"message":"private-validation-marker","trace":"raw-stack-marker"}
                """);

        assertThatThrownBy(() -> client.listTransactions(
                ACCESS, ACCOUNT_ID, null, null, 0, 20))
                .isInstanceOf(MockBankRejectedRequestException.class)
                .hasMessage("Mock-bank rejected the request")
                .hasMessageNotContaining("private-validation-marker")
                .hasMessageNotContaining("raw-stack-marker");
    }

    @Test
    void mapsUpstreamServerFailureToAvailabilityException() {
        expectError(ACCOUNT_PATH + "/balance", HttpStatus.INTERNAL_SERVER_ERROR, """
                {"message":"private-server-marker"}
                """);

        assertThatThrownBy(() -> client.getBalance(ACCESS, ACCOUNT_ID))
                .isInstanceOf(MockBankUnavailableException.class)
                .hasMessage("Mock-bank service is unavailable")
                .hasMessageNotContaining("private-server-marker");
    }

    @Test
    void mapsConnectionTimeoutToAvailabilityException() {
        server.expect(requestTo(BASE_URL + ACCOUNT_PATH + "/balance"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withException(new SocketTimeoutException("private-network-marker")));

        assertThatThrownBy(() -> client.getBalance(ACCESS, ACCOUNT_ID))
                .isInstanceOf(MockBankUnavailableException.class)
                .hasMessage("Mock-bank service is unavailable")
                .hasMessageNotContaining("private-network-marker");
    }

    @Test
    void mapsIncompatibleSuccessJsonToProtocolExceptionWithoutLeakingContent() {
        server.expect(requestTo(BASE_URL + ACCOUNT_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"externalAccountId":{"private-success-marker":true}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getAccount(ACCESS, ACCOUNT_ID))
                .isInstanceOf(MockBankProtocolException.class)
                .hasMessage("Mock-bank response violated the expected contract")
                .hasMessageNotContaining("private-success-marker");
    }

    private void expectError(String pathAndQuery, HttpStatus status, String body) {
        server.expect(requestTo(BASE_URL + pathAndQuery))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));
    }

    @Test
    void authenticatesWithExactLoginContractAndMapsTokenMetadata() {
        server.expect(requestTo(BASE_URL + "/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "accessToken":"provider-token",
                          "tokenType":"Bearer",
                          "expiresAt":"2026-08-02T20:00:00Z",
                          "customerId":"MOCK-CUST-001"
                        }
                        """, MediaType.APPLICATION_JSON));

        var login = client.login("amina.demo", "DemoBank2026!");

        assertThat(login.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(login.accessToken()).isEqualTo("provider-token");
        assertThat(login.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-08-02T20:00:00Z").toInstant());
        assertThat(login.toString()).doesNotContain("provider-token");
    }

    @Test
    void distinguishesInvalidCredentialsFromInvalidProviderToken() {
        server.expect(requestTo(BASE_URL + "/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"private-login-marker\"}"));

        assertThatThrownBy(() -> client.login("amina.demo", "wrong"))
                .isInstanceOf(InvalidMockBankCredentialsException.class)
                .hasMessageNotContaining("private-login-marker");

        server.reset();
        expectError(ACCOUNTS_PATH, HttpStatus.UNAUTHORIZED,
                "{\"message\":\"private-token-marker\"}");
        assertThatThrownBy(() -> client.listAccounts(ACCESS))
                .isInstanceOf(MockBankAuthenticationException.class)
                .hasMessageNotContaining("private-token-marker");
    }

    private static String accountJson() {
        return """
                {
                  "externalAccountId":"MOCK-ACC-001",
                  "displayName":"Everyday account",
                  "accountType":"CHECKING",
                  "currency":"TND",
                  "maskedAccountReference":"****0001",
                  "status":"ACTIVE",
                  "createdAt":"2026-01-10T09:30:00Z"
                }
                """;
    }

    private static String transactionPageJson() {
        return """
                {
                  "items":[{
                    "externalTransactionId":"MOCK-TXN-001",
                    "bookingDate":"2026-06-14",
                    "valueDate":"2026-06-15",
                    "amount":1250.3400,
                    "direction":"CREDIT",
                    "currency":"TND",
                    "description":"Salary payment",
                    "counterpartyName":"Example employer",
                    "status":"POSTED"
                  }],
                  "page":0,
                  "size":20,
                  "totalElements":1,
                  "totalPages":1,
                  "first":true,
                  "last":true
                }
                """;
    }

    private static String emptyPageJson(int page, int size) {
        return """
                {
                  "items":[],
                  "page":%d,
                  "size":%d,
                  "totalElements":0,
                  "totalPages":0,
                  "first":false,
                  "last":true
                }
                """.formatted(page, size);
    }
}
