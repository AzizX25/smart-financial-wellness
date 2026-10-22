package com.azizx25.mockbank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azizx25.mockbank.config.MockBankSecurityProperties;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
class MockBankAuthenticationIntegrationTests {

    private static final String CUSTOMER_ONE = "MOCK-CUST-001";
    private static final String CUSTOMER_TWO = "MOCK-CUST-002";
    private static final String DEMO_USERNAME = "amina.demo";
    private static final String DEMO_PASSWORD = "DemoBank2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MockBankSecurityProperties securityProperties;

    @Test
    void successfulLoginReturnsShortLivedCustomerJwtWithoutSensitiveFields() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(DEMO_USERNAME, DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header -> assertThat(header.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                        .contains("no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ONE))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("accessToken").asText();
        mockMvc.perform(get(accountsPath(CUSTOMER_ONE)).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void wrongUsernameAndPasswordReturnTheSameControlledError() throws Exception {
        assertInvalidCredentials("unknown.demo", DEMO_PASSWORD);
        assertInvalidCredentials(DEMO_USERNAME, "wrong-password");
    }

    @Test
    void seededPasswordIsStoredOnlyAsBcryptHash() {
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM mock_bank.bank_customer WHERE username = ?",
                String.class,
                DEMO_USERNAME);

        assertThat(hash).startsWith("$2").doesNotContain(DEMO_PASSWORD);
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, hash)).isTrue();
    }

    @Test
    void missingMalformedExpiredWrongIssuerAndWrongAudienceTokensAreRejected() throws Exception {
        String path = accountsPath(CUSTOMER_ONE);
        expectUnauthorized(mockMvc.perform(get(path)), path);
        expectUnauthorized(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer malformed")), path);

        Instant now = Instant.now();
        expectUnauthorized(mockMvc.perform(get(path).header(
                HttpHeaders.AUTHORIZATION,
                bearer(token(
                        securityProperties.jwt().issuer(),
                        securityProperties.jwt().audience(),
                        now.minusSeconds(120),
                        now.minusSeconds(60))))), path);
        expectUnauthorized(mockMvc.perform(get(path).header(
                HttpHeaders.AUTHORIZATION,
                bearer(token(
                        "wrong-issuer",
                        securityProperties.jwt().audience(),
                        now,
                        now.plusSeconds(60))))), path);
        expectUnauthorized(mockMvc.perform(get(path).header(
                HttpHeaders.AUTHORIZATION,
                bearer(token(
                        securityProperties.jwt().issuer(),
                        "wrong-audience",
                        now,
                        now.plusSeconds(60))))), path);
    }

    @Test
    void jwtCustomerCanAccessOnlyOwnCustomerAndOwnedAccounts() throws Exception {
        String token = loginToken();

        mockMvc.perform(get(accountsPath(CUSTOMER_ONE))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        String crossCustomerPath = accountsPath(CUSTOMER_TWO);
        mockMvc.perform(get(crossCustomerPath)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found"))
                .andExpect(jsonPath("$.path").value(crossCustomerPath));

        String foreignAccountPath = accountsPath(CUSTOMER_ONE) + "/MOCK-ACC-003";
        mockMvc.perform(get(foreignAccountPath)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found"));
    }

    private void assertInvalidCredentials(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid bank credentials"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    private void expectUnauthorized(
            org.springframework.test.web.servlet.ResultActions result,
            String path) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid or expired bank authentication"))
                .andExpect(jsonPath("$.path").value(path));
    }

    private String loginToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(DEMO_USERNAME, DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private String token(String issuer, String audience, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(CUSTOMER_ONE)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims))
                .getTokenValue();
    }

    private static String loginJson(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String accountsPath(String customerId) {
        return "/api/v1/customers/" + customerId + "/accounts";
    }
}
