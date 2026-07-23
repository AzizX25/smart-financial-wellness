package com.azizx25.smartfinancialwellness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class AuthenticationProfileIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validRegistrationCreatesTruthfulPendingAccountAndDefaultProfile() throws Exception {
        Registration registration = register();

        assertThat(registration.cookie().isHttpOnly()).isTrue();
        assertThat(registration.cookie().getPath()).isEqualTo("/api/auth");
        assertThat(registration.cookie().getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(registration.accessToken()).isNotBlank();

        UserState state = jdbcTemplate.queryForObject(
                "SELECT u.status, i.status, i.verified_at IS NULL "
                        + "FROM " + SCHEMA + "app_user u "
                        + "JOIN " + SCHEMA + "authentication_identity i ON i.user_id = u.id "
                        + "WHERE u.id = ?",
                (resultSet, rowNumber) -> new UserState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3)),
                registration.userId());
        Integer preferenceRows = jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM " + SCHEMA + "interface_preference WHERE user_id = ?) "
                        + "+ (SELECT COUNT(*) FROM " + SCHEMA + "financial_preference WHERE user_id = ?)",
                Integer.class,
                registration.userId(),
                registration.userId());

        assertThat(state).isEqualTo(new UserState("PENDING_VERIFICATION", "PENDING_VERIFICATION", true));
        assertThat(preferenceRows).isEqualTo(2);
    }

    @Test
    void duplicateEmailIsRejectedAfterNormalization() throws Exception {
        String localPart = "duplicate-" + UUID.randomUUID();
        register(localPart + "@Example.Test");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(localPart + "@example.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account already exists for this email address"));
    }

    @Test
    void registrationStoresOnlyBcryptPasswordHash() throws Exception {
        Registration registration = register();

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT p.password_hash FROM " + SCHEMA + "password_credential p "
                        + "JOIN " + SCHEMA + "authentication_identity i "
                        + "ON i.id = p.authentication_identity_id WHERE i.user_id = ?",
                String.class,
                registration.userId());
        String algorithm = jdbcTemplate.queryForObject(
                "SELECT p.password_algorithm FROM " + SCHEMA + "password_credential p "
                        + "JOIN " + SCHEMA + "authentication_identity i "
                        + "ON i.id = p.authentication_identity_id WHERE i.user_id = ?",
                String.class,
                registration.userId());

        assertThat(storedHash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, storedHash)).isTrue();
        assertThat(algorithm).isEqualTo("BCRYPT");
    }

    @Test
    void validLoginSucceedsAndInvalidCredentialsAreRejected() throws Exception {
        Registration registration = register();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(registration.email(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(registration.email()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(registration.email(), "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    void pendingUserCanRetrieveCurrentUserAndProfile() throws Exception {
        Registration registration = register();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registration.userId().toString()))
                .andExpect(jsonPath("$.accountStatus").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.identityStatus").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        mockMvc.perform(get("/api/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(registration.email()))
                .andExpect(jsonPath("$.interfacePreferences.languageTag").value("fr-TN"))
                .andExpect(jsonPath("$.financialPreferences.primaryCurrency").value("TND"));
    }

    @Test
    void validProfileUpdatePersistsOnlyEditableFields() throws Exception {
        Registration registration = register();
        String update = """
                {
                  "firstName": "Aziza",
                  "lastName": "Ben Salah",
                  "displayName": "Aziza B.",
                  "interfacePreferences": {
                    "languageTag": "en-US",
                    "timeZone": "Europe/Paris",
                    "theme": "DARK",
                    "textSize": "LARGE",
                    "highContrast": true,
                    "reducedMotion": true
                  },
                  "financialPreferences": {
                    "primaryCurrency": "EUR",
                    "monthStartDay": 5,
                    "lowBalanceThreshold": 125.5000,
                    "hideHomeAmounts": true
                  }
                }
                """;

        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Aziza B."))
                .andExpect(jsonPath("$.email").value(registration.email()))
                .andExpect(jsonPath("$.interfacePreferences.theme").value("DARK"))
                .andExpect(jsonPath("$.financialPreferences.primaryCurrency").value("EUR"));

        String persisted = jdbcTemplate.queryForObject(
                "SELECT u.display_name || '|' || ip.language_tag || '|' || BTRIM(fp.primary_currency) "
                        + "FROM " + SCHEMA + "app_user u "
                        + "JOIN " + SCHEMA + "interface_preference ip ON ip.user_id = u.id "
                        + "JOIN " + SCHEMA + "financial_preference fp ON fp.user_id = u.id "
                        + "WHERE u.id = ?",
                String.class,
                registration.userId());
        assertThat(persisted).isEqualTo("Aziza B.|en-US|EUR");
    }

    @Test
    void invalidOrReadOnlyProfileDataIsRejected() throws Exception {
        Registration registration = register();
        String invalid = """
                {
                  "displayName": "Valid name",
                  "email": "attacker@example.test",
                  "interfacePreferences": {
                    "languageTag": "en-US",
                    "timeZone": "Not/AZone",
                    "theme": "SYSTEM",
                    "textSize": "NORMAL",
                    "highContrast": false,
                    "reducedMotion": false
                  },
                  "financialPreferences": {
                    "primaryCurrency": "TND",
                    "monthStartDay": 29,
                    "hideHomeAmounts": false
                  }
                }
                """;

        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        String email = jdbcTemplate.queryForObject(
                "SELECT normalized_identifier FROM " + SCHEMA + "authentication_identity WHERE user_id = ?",
                String.class,
                registration.userId());
        assertThat(email).isEqualTo(registration.email());
    }

    @Test
    void refreshRotationStoresSha256HashesAndReuseRevokesEntireFamily() throws Exception {
        Registration registration = register();
        Cookie originalCookie = registration.cookie();

        MvcResult rotation = mockMvc.perform(post("/api/auth/refresh").cookie(originalCookie))
                .andExpect(status().isOk())
                .andReturn();
        Cookie successorCookie = rotation.getResponse().getCookie("sfwa_refresh");
        assertThat(successorCookie).isNotNull();
        assertThat(successorCookie.getValue()).isNotEqualTo(originalCookie.getValue());

        String originalHash = sha256(originalCookie.getValue());
        String familyId = jdbcTemplate.queryForObject(
                "SELECT family_id::text FROM " + SCHEMA + "refresh_session WHERE token_hash = ?",
                String.class,
                originalHash);
        Integer familySize = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "refresh_session WHERE family_id = ?::uuid",
                Integer.class,
                familyId);
        Integer rawTokensStored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "refresh_session "
                        + "WHERE token_hash IN (?, ?)",
                Integer.class,
                originalCookie.getValue(),
                successorCookie.getValue());
        assertThat(familySize).isEqualTo(2);
        assertThat(rawTokensStored).isZero();
        assertThat(originalHash).hasSize(64);

        mockMvc.perform(post("/api/auth/refresh").cookie(originalCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token reuse was detected"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        Integer revokedFamily = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "refresh_session "
                        + "WHERE family_id = ?::uuid AND revoked_at IS NOT NULL "
                        + "AND revocation_reason = 'TOKEN_REUSE_DETECTED'",
                Integer.class,
                familyId);
        Boolean reuseDetected = jdbcTemplate.queryForObject(
                "SELECT reuse_detected_at IS NOT NULL FROM " + SCHEMA + "refresh_session WHERE token_hash = ?",
                Boolean.class,
                originalHash);
        assertThat(revokedFamily).isEqualTo(2);
        assertThat(reuseDetected).isTrue();
    }

    @Test
    void logoutRevokesRefreshSessionAndClearsCookieConsistently() throws Exception {
        Registration registration = register();

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken()))
                        .cookie(registration.cookie()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("sfwa_refresh="),
                        org.hamcrest.Matchers.containsString("Path=/api/auth"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Max-Age=0"))));

        String reason = jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM " + SCHEMA + "refresh_session WHERE token_hash = ?",
                String.class,
                sha256(registration.cookie().getValue()));
        assertThat(reason).isEqualTo("LOGOUT");

        mockMvc.perform(post("/api/auth/refresh").cookie(registration.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingAccountCannotAccessBusinessEndpoints() throws Exception {
        Registration registration = register();

        mockMvc.perform(get("/api/v1/protected-probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(registration.accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void databaseStatusChangesInvalidateUnexpiredJwtLoginAndRefresh() throws Exception {
        Registration suspended = register();
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "app_user SET status = 'SUSPENDED' WHERE id = ?",
                suspended.userId());

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(suspended.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(suspended.email(), PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(suspended.cookie()))
                .andExpect(status().isUnauthorized());

        Registration disabled = register();
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "authentication_identity SET status = 'DISABLED' WHERE user_id = ?",
                disabled.userId());
        mockMvc.perform(get("/api/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(disabled.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(disabled.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bcryptLimitIsValidatedUsingUtf8Bytes() throws Exception {
        String overSeventyTwoBytes = "é".repeat(37);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("utf8-" + UUID.randomUUID() + "@example.test")
                                .replace(PASSWORD, overSeventyTwoBytes)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    private Registration register() throws Exception {
        return register("user-" + UUID.randomUUID() + "@example.test");
    }

    private Registration register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("sfwa_refresh");
        assertThat(cookie).isNotNull();
        return new Registration(
                email.toLowerCase(),
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText(),
                cookie);
    }

    private String registrationJson(String email) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "firstName": "Test",
                  "lastName": "User",
                  "displayName": "Test User"
                }
                """.formatted(email, PASSWORD);
    }

    private String loginJson(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Registration(String email, UUID userId, String accessToken, Cookie cookie) {
    }

    private record UserState(String userStatus, String identityStatus, boolean verifiedAtIsNull) {
    }
}
