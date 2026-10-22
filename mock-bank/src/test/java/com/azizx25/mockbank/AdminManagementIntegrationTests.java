package com.azizx25.mockbank;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azizx25.mockbank.config.MockBankSecurityProperties;
import com.azizx25.mockbank.security.MockBankJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
class AdminManagementIntegrationTests {

    private static final String ADMIN_USERNAME = "admin.demo";
    private static final String ADMIN_PASSWORD = "AdminBank2026!";
    private static final String ADMIN_UI_ORIGIN = "http://localhost:5174";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockBankJwtService jwtService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MockBankSecurityProperties securityProperties;

    @Test
    void adminCorsPreflightAllowsOnlyTheConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/admin/auth/login")
                        .header(HttpHeaders.ORIGIN, ADMIN_UI_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        ADMIN_UI_ORIGIN))
                .andExpect(result -> assertThat(result.getResponse().getHeader(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST"));

        mockMvc.perform(options("/api/admin/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://unapproved.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @AfterEach
    void removeGeneratedAdminTestFixtures() {
        String generatedCustomers = "customer.username LIKE 'managed.%' "
                + "OR customer.username LIKE 'customer.%' "
                + "OR customer.username LIKE 'operations.%'";
        jdbcTemplate.update(
                "DELETE FROM mock_bank.bank_transaction bank_txn USING mock_bank.bank_account account, "
                        + "mock_bank.bank_customer customer WHERE bank_txn.bank_account_id = account.id "
                        + "AND account.bank_customer_id = customer.id AND (" + generatedCustomers + ")");
        jdbcTemplate.update(
                "DELETE FROM mock_bank.bank_account account USING mock_bank.bank_customer customer "
                        + "WHERE account.bank_customer_id = customer.id AND (" + generatedCustomers + ")");
        jdbcTemplate.update(
                "DELETE FROM mock_bank.bank_customer customer WHERE " + generatedCustomers);
    }

    @Test
    void adminLoginUsesBcryptAndReturnsRoleSeparatedJwtWithoutSensitiveFields() throws Exception {
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM mock_bank.bank_administrator WHERE username = ?",
                String.class,
                ADMIN_USERNAME);
        assertThat(hash).startsWith("$2").doesNotContain(ADMIN_PASSWORD);
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, hash)).isTrue();

        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(ADMIN_USERNAME, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header -> assertThat(header.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                        .contains("no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value(ADMIN_USERNAME))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String token = body(result).get("accessToken").asText();
        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").isNumber())
                .andExpect(jsonPath("$.recentTransactions").isArray());
    }

    @Test
    void wrongAdminCredentialsReturnControlledGenericError() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(ADMIN_USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Invalid bank credentials"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void invalidAdminTokensAreRejectedAndRolesCannotCrossBoundaries() throws Exception {
        String path = "/api/admin/dashboard";
        expectUnauthorized(mockMvc.perform(get(path)), path);
        expectUnauthorized(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer malformed")), path);
        Instant now = Instant.now();
        expectUnauthorized(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(customAdminToken(
                securityProperties.jwt().issuer(),
                securityProperties.jwt().audience(),
                now.minusSeconds(120),
                now.minusSeconds(60))))), path);
        expectUnauthorized(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(customAdminToken(
                "wrong-issuer",
                securityProperties.jwt().audience(),
                now,
                now.plusSeconds(60))))), path);
        expectUnauthorized(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(customAdminToken(
                securityProperties.jwt().issuer(),
                "wrong-audience",
                now,
                now.plusSeconds(60))))), path);

        String customerToken = jwtService.issue("MOCK-CUST-001").value();
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
                .andExpect(status().isForbidden());

        String adminToken = adminToken();
        mockMvc.perform(get("/api/v1/customers/MOCK-CUST-001/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerManagementNormalizesUniquenessHashesResetsAndEnforcesStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "managed." + suffix;
        String password = "CustomerPass2026!";
        String newPassword = "ChangedPass2026!";
        String adminToken = adminToken();
        MvcResult created = createCustomer(adminToken, username, password);
        String customerId = body(created).get("customerId").asText();

        mockMvc.perform(get("/api/admin/customers?search=" + suffix)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].customerId").value(customerId))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());

        mockMvc.perform(post("/api/admin/customers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(username.toUpperCase(), "AnotherPass2026!")))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/admin/customers/{id}", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Client modifié\",\"countryCode\":\"fr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Client modifié"))
                .andExpect(jsonPath("$.countryCode").value("FR"));

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM mock_bank.bank_customer WHERE external_customer_id = ?",
                String.class,
                customerId);
        assertThat(passwordEncoder.matches(password, storedHash)).isTrue();
        assertThat(storedHash).doesNotContain(password);

        mockMvc.perform(post("/api/admin/customers/{id}/reset-password", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isNoContent());
        loginCustomer(username, password).andExpect(status().isUnauthorized());
        String activeCustomerToken = customerLoginToken(username, newPassword);

        mockMvc.perform(post("/api/admin/customers/{id}/deactivate", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        loginCustomer(username, newPassword).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(activeCustomerToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/customers/{id}/activate", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        loginCustomer(username, newPassword).andExpect(status().isOk());
    }

    @Test
    void accountCreationHasZeroBalanceCannotMoveAndLifecycleIsControlled() throws Exception {
        String adminToken = adminToken();
        String customerId = newCustomerId(adminToken);
        String accountId = newAccountId(adminToken, customerId);

        mockMvc.perform(get("/api/admin/accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.currentBalance").value(0.0))
                .andExpect(jsonPath("$.availableBalance").value(0.0))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(patch("/api/admin/accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"MOCK-CUST-002\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/accounts/{id}/freeze", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(post("/api/admin/accounts/{id}/activate", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/accounts/{id}/close", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/admin/accounts/{id}/activate", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void creditsAndDebitsAtomicallyUpdateBalancesAndCustomerHistory() throws Exception {
        String adminToken = adminToken();
        MvcResult customer = createCustomer(
                adminToken,
                "operations." + UUID.randomUUID().toString().substring(0, 8),
                "OperationsPass2026!");
        JsonNode customerBody = body(customer);
        String customerId = customerBody.get("customerId").asText();
        String username = customerBody.get("username").asText();
        String accountId = newAccountId(adminToken, customerId);

        String creditReference = "REF-CREDIT-" + UUID.randomUUID();
        createTransaction(adminToken, accountId, "100.0000", "CREDIT", creditReference)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("TND"));
        createTransaction(adminToken, accountId, "30.2500", "DEBIT", "REF-DEBIT-" + UUID.randomUUID())
                .andExpect(status().isCreated());
        createTransaction(adminToken, accountId, "100.0000", "CREDIT", creditReference)
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/admin/accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(69.75))
                .andExpect(jsonPath("$.availableBalance").value(69.75));
        mockMvc.perform(get("/api/admin/accounts/{id}/transactions", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        String customerToken = customerLoginToken(username, "OperationsPass2026!");
        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts/{accountId}/balance", customerId, accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(69.75));
        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts/{accountId}/transactions", customerId, accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void invalidInsufficientAndNonActiveOperationsLeaveBalancesAndHistoryUnchanged() throws Exception {
        String adminToken = adminToken();
        String accountId = newAccountId(adminToken, newCustomerId(adminToken));
        createTransaction(adminToken, accountId, "0", "CREDIT", null)
                .andExpect(status().isBadRequest());
        createTransaction(adminToken, accountId, "10.0000", "DEBIT", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Insufficient funds"));
        assertBalanceAndCount(accountId, BigDecimal.ZERO, 0);

        mockMvc.perform(post("/api/admin/accounts/{id}/freeze", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
        createTransaction(adminToken, accountId, "10.0000", "CREDIT", null)
                .andExpect(status().isUnprocessableEntity());
        assertBalanceAndCount(accountId, BigDecimal.ZERO, 0);
    }

    private MvcResult createCustomer(String token, String username, String password) throws Exception {
        return mockMvc.perform(post("/api/admin/customers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username.toLowerCase()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
    }

    private String newCustomerId(String token) throws Exception {
        return body(createCustomer(
                token,
                "customer." + UUID.randomUUID().toString().substring(0, 8),
                "CustomerPass2026!"))
                .get("customerId").asText();
    }

    private String newAccountId(String token, String customerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/customers/{id}/accounts", customerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Compte de test","accountType":"CHECKING","currency":"TND"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).get("accountId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions createTransaction(
            String token,
            String accountId,
            String amount,
            String direction,
            String reference) throws Exception {
        String referenceJson = reference == null ? "null" : "\"" + reference + "\"";
        return mockMvc.perform(post("/api/admin/accounts/{id}/transactions", accountId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%s,"direction":"%s","description":"Opération de test","reference":%s}
                        """.formatted(amount, direction, referenceJson)));
    }

    private void assertBalanceAndCount(String accountId, BigDecimal balance, int transactions) {
        BigDecimal storedBalance = jdbcTemplate.queryForObject(
                "SELECT current_balance FROM mock_bank.bank_account WHERE external_account_id = ?",
                BigDecimal.class,
                accountId);
        Integer storedTransactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_bank.bank_transaction bank_txn "
                        + "JOIN mock_bank.bank_account account ON account.id = bank_txn.bank_account_id "
                        + "WHERE account.external_account_id = ?",
                Integer.class,
                accountId);
        assertThat(storedBalance).isEqualByComparingTo(balance);
        assertThat(storedTransactions).isEqualTo(transactions);
    }

    private org.springframework.test.web.servlet.ResultActions loginCustomer(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(username, password)));
    }

    private String customerLoginToken(String username, String password) throws Exception {
        MvcResult result = loginCustomer(username, password).andExpect(status().isOk()).andReturn();
        return body(result).get("accessToken").asText();
    }

    private String adminToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(ADMIN_USERNAME, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("accessToken").asText();
    }

    private String customAdminToken(String issuer, String audience, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(ADMIN_USERNAME)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .claim("token_type", "ADMIN")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void expectUnauthorized(
            org.springframework.test.web.servlet.ResultActions result,
            String path) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(path));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String customerJson(String username, String password) {
        return """
                {"displayName":"Client de test","username":"%s","password":"%s","countryCode":"TN"}
                """.formatted(username, password);
    }

    private static String loginJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
