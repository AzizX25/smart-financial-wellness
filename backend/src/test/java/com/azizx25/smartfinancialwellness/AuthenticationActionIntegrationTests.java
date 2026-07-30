package com.azizx25.smartfinancialwellness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.AuthService;
import com.azizx25.smartfinancialwellness.auth.api.AuthDtos;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class AuthenticationActionIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final String ORIGINAL_PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingEmailDeliveryService emailDeliveryService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearCapturedEmails() {
        emailDeliveryService.clear();
    }

    @Test
    void registrationCreatesPendingVerificationTokenAndCapturesEmailWithoutStoringRawValue() throws Exception {
        Registration registration = register();
        CapturingEmailDeliveryService.CapturedEmail email = verificationEmail(registration);

        TokenState token = jdbcTemplate.queryForObject(
                "SELECT action_type, token_hash, used_at IS NULL, invalidated_at IS NULL "
                        + "FROM " + SCHEMA + "account_action_token "
                        + "WHERE authentication_identity_id = ?",
                (resultSet, rowNumber) -> new TokenState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3),
                        resultSet.getBoolean(4)),
                registration.identityId());

        assertThat(token).isNotNull();
        assertThat(token.actionType()).isEqualTo("EMAIL_VERIFICATION");
        assertThat(token.tokenHash()).isEqualTo(sha256(email.rawToken())).hasSize(64);
        assertThat(token.tokenHash()).isNotEqualTo(email.rawToken());
        assertThat(token.unused()).isTrue();
        assertThat(token.valid()).isTrue();
        assertThat(email.recipient()).isEqualTo(registration.email());
        assertThat(email.displayName()).isEqualTo("Test User");
    }

    @Test
    void rolledBackRegistrationDoesNotDispatchVerificationEmail() {
        String email = "rollback-" + UUID.randomUUID() + "@example.test";
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            authService.register(new AuthDtos.RegisterRequest(
                    email,
                    ORIGINAL_PASSWORD,
                    "Test",
                    "Rollback",
                    "Test Rollback"));
            status.setRollbackOnly();
        });

        Integer identities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "authentication_identity "
                        + "WHERE normalized_identifier = ?",
                Integer.class,
                email);
        Integer tokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "account_action_token token "
                        + "JOIN " + SCHEMA + "authentication_identity identity "
                        + "ON identity.id = token.authentication_identity_id "
                        + "WHERE identity.normalized_identifier = ?",
                Integer.class,
                email);

        assertThat(identities).isZero();
        assertThat(tokens).isZero();
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.VERIFICATION))
                .isEmpty();
    }

    @Test
    void registrationSurvivesEmailFailureAndCanResendAfterward() throws Exception {
        emailDeliveryService.failDeliveries();

        Registration registration = register();

        assertThat(activationState(registration.userId()))
                .isEqualTo(new ActivationState(
                        "PENDING_VERIFICATION",
                        "PENDING_VERIFICATION",
                        false));
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.VERIFICATION))
                .isEmpty();
        Integer usableTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "account_action_token "
                        + "WHERE authentication_identity_id = ? "
                        + "AND action_type = 'EMAIL_VERIFICATION' "
                        + "AND used_at IS NULL AND invalidated_at IS NULL "
                        + "AND expires_at > CURRENT_TIMESTAMP",
                Integer.class,
                registration.identityId());
        assertThat(usableTokens).isOne();

        ageLatestActionToken(registration.identityId(), "EMAIL_VERIFICATION");
        MvcResult failedDeliveryResponse = resendVerification(registration.email())
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult unknownResponse = resendVerification(
                "missing-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(failedDeliveryResponse.getResponse().getContentAsString())
                .isEqualTo(unknownResponse.getResponse().getContentAsString());
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.VERIFICATION))
                .isEmpty();

        emailDeliveryService.allowDeliveries();
        ageLatestActionToken(registration.identityId(), "EMAIL_VERIFICATION");
        resendVerification(registration.email()).andExpect(status().isAccepted());
        String replacementToken = verificationEmail(registration).rawToken();
        confirmVerification(replacementToken).andExpect(status().isOk());

        assertThat(auditCount(
                "AUTH_EMAIL_VERIFICATION_DELIVERY_FAILED",
                registration.identityId(),
                "FAILED")).isEqualTo(2);
        assertAuditDetailsAreEmpty("AUTH_EMAIL_VERIFICATION_DELIVERY_FAILED");
    }

    @Test
    void validVerificationAtomicallyActivatesIdentityAndUser() throws Exception {
        Registration registration = register();

        confirmVerification(verificationEmail(registration).rawToken())
                .andExpect(status().isOk());

        ActivationState state = activationState(registration.userId());
        Boolean tokenConsumed = jdbcTemplate.queryForObject(
                "SELECT used_at IS NOT NULL FROM " + SCHEMA + "account_action_token "
                        + "WHERE authentication_identity_id = ? AND action_type = 'EMAIL_VERIFICATION'",
                Boolean.class,
                registration.identityId());
        assertThat(state).isEqualTo(new ActivationState("ACTIVE", "ACTIVE", true));
        assertThat(tokenConsumed).isTrue();
        assertThat(auditCount(
                "AUTH_EMAIL_VERIFIED",
                registration.identityId(),
                "SUCCEEDED")).isOne();
        assertAuditDetailsAreEmpty("AUTH_EMAIL_VERIFIED");
    }

    @Test
    void invalidExpiredConsumedAndWrongPurposeVerificationTokensFailSafely() throws Exception {
        int rejectedBefore = auditCount("AUTH_EMAIL_VERIFICATION_REJECTED");

        confirmVerification("not-a-valid-token")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The account action token is invalid or has expired"));

        Registration expired = register();
        String expiredToken = verificationEmail(expired).rawToken();
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "account_action_token "
                        + "SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days', "
                        + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' "
                        + "WHERE token_hash = ?",
                sha256(expiredToken));
        confirmVerification(expiredToken).andExpect(status().isBadRequest());

        Registration consumed = register();
        String consumedToken = verificationEmail(consumed).rawToken();
        confirmVerification(consumedToken).andExpect(status().isOk());
        confirmVerification(consumedToken).andExpect(status().isBadRequest());

        requestPasswordReset(consumed.email()).andExpect(status().isAccepted());
        String passwordResetToken = emailDeliveryService
                .latest(CapturingEmailDeliveryService.Type.PASSWORD_RESET, consumed.email())
                .rawToken();
        confirmVerification(passwordResetToken).andExpect(status().isBadRequest());

        assertThat(auditCount("AUTH_EMAIL_VERIFICATION_REJECTED") - rejectedBefore)
                .isEqualTo(4);
        assertAuditDetailsAreEmpty("AUTH_EMAIL_VERIFICATION_REJECTED");
    }

    @Test
    void resendInvalidatesPreviousVerificationTokenAndDoesNotRevealAccountExistence() throws Exception {
        Registration registration = register();
        String originalToken = verificationEmail(registration).rawToken();

        MvcResult cooldownResponse = resendVerification(registration.email())
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.VERIFICATION))
                .hasSize(1);

        ageLatestActionToken(registration.identityId(), "EMAIL_VERIFICATION");
        MvcResult knownAccount = resendVerification(registration.email())
                .andExpect(status().isAccepted())
                .andReturn();
        String replacementToken = verificationEmail(registration).rawToken();
        MvcResult unknownAccount = resendVerification("missing-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isAccepted())
                .andReturn();

        Boolean originalInvalidated = jdbcTemplate.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM " + SCHEMA + "account_action_token "
                        + "WHERE token_hash = ?",
                Boolean.class,
                sha256(originalToken));
        assertThat(originalInvalidated).isTrue();
        assertThat(replacementToken).isNotEqualTo(originalToken);
        assertThat(knownAccount.getResponse().getContentAsString())
                .isEqualTo(cooldownResponse.getResponse().getContentAsString())
                .isEqualTo(unknownAccount.getResponse().getContentAsString());
        confirmVerification(originalToken).andExpect(status().isBadRequest());
        confirmVerification(replacementToken).andExpect(status().isOk());
        MvcResult activeAccount = resendVerification(registration.email())
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(activeAccount.getResponse().getContentAsString())
                .isEqualTo(knownAccount.getResponse().getContentAsString());

        assertThat(auditCount(
                "AUTH_EMAIL_VERIFICATION_RESEND_THROTTLED",
                registration.identityId(),
                "DENIED")).isOne();
        assertThat(auditCount(
                "AUTH_EMAIL_VERIFICATION_REQUESTED",
                registration.identityId(),
                "SUCCEEDED")).isOne();
    }

    @Test
    void suspendedAccountCannotBeReactivatedWithAnOldVerificationToken() throws Exception {
        Registration registration = register();
        String token = verificationEmail(registration).rawToken();
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "app_user SET status = 'SUSPENDED' WHERE id = ?",
                registration.userId());

        confirmVerification(token).andExpect(status().isBadRequest());

        ActivationState state = activationState(registration.userId());
        assertThat(state.userStatus()).isEqualTo("SUSPENDED");
        assertThat(state.identityStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(state.verified()).isFalse();
    }

    @Test
    void passwordResetRequestIsGenericAndEmailsOnlyEligibleVerifiedAccounts() throws Exception {
        Registration eligible = register();
        confirmVerification(verificationEmail(eligible).rawToken()).andExpect(status().isOk());
        Registration pending = register();

        MvcResult eligibleResponse = requestPasswordReset(eligible.email())
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult cooldownResponse = requestPasswordReset(eligible.email())
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult pendingResponse = requestPasswordReset(pending.email())
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult missingResponse = requestPasswordReset("missing-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(eligibleResponse.getResponse().getContentAsString())
                .isEqualTo(cooldownResponse.getResponse().getContentAsString())
                .isEqualTo(pendingResponse.getResponse().getContentAsString())
                .isEqualTo(missingResponse.getResponse().getContentAsString());
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.PASSWORD_RESET))
                .singleElement()
                .extracting(CapturingEmailDeliveryService.CapturedEmail::recipient)
                .isEqualTo(eligible.email());

        ageLatestActionToken(eligible.identityId(), "PASSWORD_RESET");
        emailDeliveryService.failDeliveries();
        MvcResult deliveryFailureResponse = requestPasswordReset(eligible.email())
                .andExpect(status().isAccepted())
                .andReturn();
        emailDeliveryService.allowDeliveries();

        assertThat(deliveryFailureResponse.getResponse().getContentAsString())
                .isEqualTo(eligibleResponse.getResponse().getContentAsString());
        assertThat(emailDeliveryService.messagesOfType(CapturingEmailDeliveryService.Type.PASSWORD_RESET))
                .hasSize(1);
        assertThat(auditCount(
                "AUTH_PASSWORD_RESET_REQUEST_THROTTLED",
                eligible.identityId(),
                "DENIED")).isOne();
        assertThat(auditCount(
                "AUTH_PASSWORD_RESET_DELIVERY_FAILED",
                eligible.identityId(),
                "FAILED")).isOne();
        assertAuditDetailsAreEmpty("AUTH_PASSWORD_RESET_DELIVERY_FAILED");
    }

    @Test
    void validPasswordResetChangesHashRevokesSessionsAndCannotBeReused() throws Exception {
        Registration registration = register();
        confirmVerification(verificationEmail(registration).rawToken()).andExpect(status().isOk());
        requestPasswordReset(registration.email()).andExpect(status().isAccepted());
        String resetToken = emailDeliveryService
                .latest(CapturingEmailDeliveryService.Type.PASSWORD_RESET, registration.email())
                .rawToken();
        String oldHash = storedPasswordHash(registration.identityId());

        confirmPasswordReset(resetToken, NEW_PASSWORD)
                .andExpect(status().isOk());

        String newHash = storedPasswordHash(registration.identityId());
        Integer activeSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "refresh_session "
                        + "WHERE authentication_identity_id = ? AND revoked_at IS NULL",
                Integer.class,
                registration.identityId());
        String originalSessionReason = jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM " + SCHEMA + "refresh_session WHERE token_hash = ?",
                String.class,
                sha256(registration.refreshCookie().getValue()));

        assertThat(newHash).isNotEqualTo(oldHash).startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches(NEW_PASSWORD, newHash)).isTrue();
        assertThat(activeSessions).isZero();
        assertThat(originalSessionReason).isEqualTo("PASSWORD_RESET");

        login(registration.email(), ORIGINAL_PASSWORD).andExpect(status().isUnauthorized());
        login(registration.email(), NEW_PASSWORD).andExpect(status().isOk());
        int rejectedBefore = auditCount("AUTH_PASSWORD_RESET_REJECTED");
        confirmPasswordReset(resetToken, NEW_PASSWORD).andExpect(status().isBadRequest());
        assertThat(auditCount("AUTH_PASSWORD_RESET_REJECTED") - rejectedBefore).isOne();
        assertThat(auditCount(
                "AUTH_PASSWORD_RESET",
                registration.identityId(),
                "SUCCEEDED")).isOne();
        assertAuditDetailsAreEmpty("AUTH_PASSWORD_RESET");
    }

    private Registration register() throws Exception {
        String email = "action-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "firstName": "Test",
                                  "lastName": "User",
                                  "displayName": "Test User"
                                }
                                """.formatted(email, ORIGINAL_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "authentication_identity WHERE user_id = ?",
                UUID.class,
                userId);
        Cookie refreshCookie = result.getResponse().getCookie("sfwa_refresh");
        assertThat(refreshCookie).isNotNull();
        return new Registration(email, userId, identityId, refreshCookie);
    }

    private CapturingEmailDeliveryService.CapturedEmail verificationEmail(Registration registration) {
        return emailDeliveryService.latest(
                CapturingEmailDeliveryService.Type.VERIFICATION,
                registration.email());
    }

    private org.springframework.test.web.servlet.ResultActions confirmVerification(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson(token)));
    }

    private org.springframework.test.web.servlet.ResultActions resendVerification(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailJson(email)));
    }

    private org.springframework.test.web.servlet.ResultActions requestPasswordReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailJson(email)));
    }

    private org.springframework.test.web.servlet.ResultActions confirmPasswordReset(
            String token,
            String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"%s"}
                        """.formatted(token, newPassword)));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    private ActivationState activationState(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT u.status, i.status, i.verified_at IS NOT NULL "
                        + "FROM " + SCHEMA + "app_user u "
                        + "JOIN " + SCHEMA + "authentication_identity i ON i.user_id = u.id "
                        + "WHERE u.id = ?",
                (resultSet, rowNumber) -> new ActivationState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3)),
                userId);
    }

    private String storedPasswordHash(UUID identityId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM " + SCHEMA + "password_credential "
                        + "WHERE authentication_identity_id = ?",
                String.class,
                identityId);
    }

    private void ageLatestActionToken(UUID identityId, String actionType) {
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "account_action_token "
                        + "SET created_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' "
                        + "WHERE authentication_identity_id = ? AND action_type = ?",
                identityId,
                actionType);
    }

    private int auditCount(String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry WHERE action = ?",
                Integer.class,
                action);
    }

    private int auditCount(String action, UUID resourceId, String outcome) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE action = ? AND resource_id = ? AND outcome = ?",
                Integer.class,
                action,
                resourceId,
                outcome);
    }

    private void assertAuditDetailsAreEmpty(String action) {
        String details = jdbcTemplate.queryForObject(
                "SELECT change_summary::text FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE action = ? ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                action);
        assertThat(details).isEqualTo("{}");
    }

    private String tokenJson(String token) {
        return """
                {"token":"%s"}
                """.formatted(token);
    }

    private String emailJson(String email) {
        return """
                {"email":"%s"}
                """.formatted(email);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Registration(String email, UUID userId, UUID identityId, Cookie refreshCookie) {
    }

    private record TokenState(String actionType, String tokenHash, boolean unused, boolean valid) {
    }

    private record ActivationState(String userStatus, String identityStatus, boolean verified) {
    }
}
