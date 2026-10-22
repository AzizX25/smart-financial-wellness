package com.azizx25.mockbank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
class TransactionControllerIntegrationTests extends AuthenticatedMockBankIntegrationTest {

    private static final String FIRST_CUSTOMER = "MOCK-CUST-001";
    private static final String FIRST_ACCOUNT = "MOCK-ACC-001";
    private static final String SAVINGS_ACCOUNT = "MOCK-ACC-002";
    private static final String SECOND_CUSTOMER_ACCOUNT = "MOCK-ACC-003";
    private static final String UNKNOWN_CUSTOMER = "MOCK-CUST-999";
    private static final String UNKNOWN_ACCOUNT = "MOCK-ACC-999";

    @Test
    void defaultPageReturnsOnlySafeTransactionsInExactOrderWithMetadata() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].length()").value(9))
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-006"))
                .andExpect(jsonPath("$.items[0].bookingDate").value("2026-07-29"))
                .andExpect(jsonPath("$.items[0].valueDate").value("2026-07-29"))
                .andExpect(jsonPath("$.items[0].amount").value(60.0))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].currency").value("TND"))
                .andExpect(jsonPath("$.items[0].description").value("Retrait DAB"))
                .andExpect(jsonPath("$.items[0].counterpartyName").value("ATM Banque Démo"))
                .andExpect(jsonPath("$.items[0].status").value("POSTED"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.items[2].externalTransactionId").value("MOCK-TXN-003"))
                .andExpect(jsonPath("$.items[3].externalTransactionId").value("MOCK-TXN-002"))
                .andExpect(jsonPath("$.items[4].externalTransactionId").value("MOCK-TXN-001"))
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].bankAccountId").doesNotExist())
                .andExpect(jsonPath("$.items[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.number").doesNotExist())
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());
    }

    @Test
    void requestedAccountReturnsOnlyItsOwnTransactions() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, SAVINGS_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-005"))
                .andExpect(jsonPath("$.items[0].amount").value(300.0))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void consecutivePagesContainNoDuplicateTransactionIdentifiers() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        mockMvc.perform(get(path).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-006"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get(path).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-003"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-002"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void customPageSizeIsAppliedInPostgreSql() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-006"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.items[2].externalTransactionId").value("MOCK-TXN-003"))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void fromFilterUsesInclusiveBookingDate() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("from", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-006"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void toFilterUsesInclusiveBookingDate() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("to", "2026-07-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-002"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-001"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void combinedRangeFiltersBookingDate() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("from", "2026-07-26")
                        .param("to", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.items[1].externalTransactionId").value("MOCK-TXN-003"))
                .andExpect(jsonPath("$.items[2].externalTransactionId").value("MOCK-TXN-002"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void equalRangeBoundariesIncludeTransactionsOnThatDate() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("from", "2026-07-28")
                        .param("to", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].externalTransactionId").value("MOCK-TXN-004"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filterCanReturnAnEmptyPage() throws Exception {
        mockMvc.perform(get(transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT))
                        .param("from", "2026-07-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void unknownCustomerReturnsNotFound() throws Exception {
        String path = transactionsPath(UNKNOWN_CUSTOMER, FIRST_ACCOUNT);

        expectError(mockMvc.perform(get(path)), 404, "Not Found", "Customer not found", path);
    }

    @Test
    void unknownAccountReturnsNotFound() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, UNKNOWN_ACCOUNT);

        expectError(mockMvc.perform(get(path)), 404, "Not Found", "Account not found", path);
    }

    @Test
    void crossCustomerAccountReturnsNonRevealingNotFound() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, SECOND_CUSTOMER_ACCOUNT);

        expectError(mockMvc.perform(get(path)), 404, "Not Found", "Account not found", path);
    }

    @Test
    void fromAfterToReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path)
                        .param("from", "2026-07-29")
                        .param("to", "2026-07-28")),
                400,
                "Bad Request",
                "Transaction from-date must not be after to-date",
                path);
    }

    @Test
    void malformedFromDateReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path).param("from", "2026-07-invalid")),
                400,
                "Bad Request",
                "Invalid value for query parameter 'from'",
                path);
    }

    @Test
    void malformedToDateReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path).param("to", "not-a-date")),
                400,
                "Bad Request",
                "Invalid value for query parameter 'to'",
                path);
    }

    @Test
    void negativePageReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path).param("page", "-1")),
                400,
                "Bad Request",
                "Transaction page must be greater than or equal to 0",
                path);
    }

    @Test
    void zeroSizeReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path).param("size", "0")),
                400,
                "Bad Request",
                "Transaction size must be between 1 and 100",
                path);
    }

    @Test
    void sizeAboveMaximumReturnsBadRequest() throws Exception {
        String path = transactionsPath(FIRST_CUSTOMER, FIRST_ACCOUNT);

        expectError(
                mockMvc.perform(get(path).param("size", "101")),
                400,
                "Bad Request",
                "Transaction size must be between 1 and 100",
                path);
    }

    private void expectError(
            ResultActions result,
            int expectedStatus,
            String expectedError,
            String expectedMessage,
            String expectedPath) throws Exception {
        result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.error").value(expectedError))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.path").value(expectedPath));
    }

    private static String transactionsPath(String customerId, String accountId) {
        return "/api/v1/customers/" + customerId
                + "/accounts/" + accountId
                + "/transactions";
    }
}
