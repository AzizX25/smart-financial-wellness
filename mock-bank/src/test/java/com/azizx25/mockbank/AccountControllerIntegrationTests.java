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
class AccountControllerIntegrationTests extends AuthenticatedMockBankIntegrationTest {

    private static final String FIRST_CUSTOMER = "MOCK-CUST-001";
    private static final String FIRST_ACCOUNT = "MOCK-ACC-001";
    private static final String SECOND_ACCOUNT = "MOCK-ACC-002";
    private static final String SECOND_CUSTOMER_ACCOUNT = "MOCK-ACC-003";
    private static final String UNKNOWN_CUSTOMER = "MOCK-CUST-999";
    private static final String UNKNOWN_ACCOUNT = "MOCK-ACC-999";

    @Test
    void listAccountsReturnsOwnedAccountsInDeterministicOrder() throws Exception {
        mockMvc.perform(get(accountsPath(FIRST_CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].externalAccountId").value(FIRST_ACCOUNT))
                .andExpect(jsonPath("$[0].accountType").value("CHECKING"))
                .andExpect(jsonPath("$[0].maskedAccountReference").value("****C001"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].bankCustomerId").doesNotExist())
                .andExpect(jsonPath("$[0].currentBalance").doesNotExist())
                .andExpect(jsonPath("$[0].availableBalance").doesNotExist())
                .andExpect(jsonPath("$[1].length()").value(7))
                .andExpect(jsonPath("$[1].externalAccountId").value(SECOND_ACCOUNT))
                .andExpect(jsonPath("$[1].accountType").value("SAVINGS"))
                .andExpect(jsonPath("$[1].maskedAccountReference").value("****C002"))
                .andExpect(jsonPath("$[1].id").doesNotExist())
                .andExpect(jsonPath("$[1].bankCustomerId").doesNotExist())
                .andExpect(jsonPath("$[1].currentBalance").doesNotExist())
                .andExpect(jsonPath("$[1].availableBalance").doesNotExist());
    }

    @Test
    void getAccountReturnsPublicAccountMetadataOnly() throws Exception {
        mockMvc.perform(get(accountPath(FIRST_CUSTOMER, FIRST_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.externalAccountId").value(FIRST_ACCOUNT))
                .andExpect(jsonPath("$.displayName").value("Compte courant principal"))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maskedAccountReference").value("****C001"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.currentBalance").doesNotExist())
                .andExpect(jsonPath("$.availableBalance").doesNotExist())
                .andExpect(jsonPath("$.bankCustomerId").doesNotExist());
    }

    @Test
    void getBalanceReturnsAccountBalances() throws Exception {
        mockMvc.perform(get(balancePath(FIRST_CUSTOMER, FIRST_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.externalAccountId").value(FIRST_ACCOUNT))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.currentBalance").value(3626.25))
                .andExpect(jsonPath("$.availableBalance").value(3626.25))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maskedAccountReference").value("****C001"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.bankCustomerId").doesNotExist())
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.accountType").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    @Test
    void listAccountsReturnsNotFoundForUnknownCustomer() throws Exception {
        String path = accountsPath(UNKNOWN_CUSTOMER);

        expectNotFound(mockMvc.perform(get(path)), "Customer not found", path);
    }

    @Test
    void getAccountReturnsNotFoundForUnknownCustomer() throws Exception {
        String path = accountPath(UNKNOWN_CUSTOMER, FIRST_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Customer not found", path);
    }

    @Test
    void getAccountReturnsNotFoundForUnknownAccount() throws Exception {
        String path = accountPath(FIRST_CUSTOMER, UNKNOWN_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Account not found", path);
    }

    @Test
    void getAccountHidesAccountOwnedByAnotherCustomer() throws Exception {
        String path = accountPath(FIRST_CUSTOMER, SECOND_CUSTOMER_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Account not found", path);
    }

    @Test
    void getBalanceReturnsNotFoundForUnknownCustomer() throws Exception {
        String path = balancePath(UNKNOWN_CUSTOMER, FIRST_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Customer not found", path);
    }

    @Test
    void getBalanceReturnsNotFoundForUnknownAccount() throws Exception {
        String path = balancePath(FIRST_CUSTOMER, UNKNOWN_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Account not found", path);
    }

    @Test
    void getBalanceHidesAccountOwnedByAnotherCustomer() throws Exception {
        String path = balancePath(FIRST_CUSTOMER, SECOND_CUSTOMER_ACCOUNT);

        expectNotFound(mockMvc.perform(get(path)), "Account not found", path);
    }

    private void expectNotFound(
            ResultActions result,
            String expectedMessage,
            String expectedPath) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.path").value(expectedPath));
    }

    private static String accountsPath(String customerId) {
        return "/api/v1/customers/" + customerId + "/accounts";
    }

    private static String accountPath(String customerId, String accountId) {
        return accountsPath(customerId) + "/" + accountId;
    }

    private static String balancePath(String customerId, String accountId) {
        return accountPath(customerId, accountId) + "/balance";
    }
}
