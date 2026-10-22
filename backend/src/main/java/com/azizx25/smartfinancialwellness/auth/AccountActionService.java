package com.azizx25.smartfinancialwellness.auth;

import java.time.Clock;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditLogEntry;
import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.auth.persistence.AccountActionToken;
import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentityRepository;
import com.azizx25.smartfinancialwellness.auth.persistence.PasswordCredential;
import com.azizx25.smartfinancialwellness.auth.persistence.PasswordCredentialRepository;
import com.azizx25.smartfinancialwellness.auth.persistence.RefreshSessionRepository;
import com.azizx25.smartfinancialwellness.common.web.ApiException;
import com.azizx25.smartfinancialwellness.config.SecurityProperties;
import com.azizx25.smartfinancialwellness.email.AfterCommitEmailDispatcher;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountActionService {

    private final AccountActionTokenService tokenService;
    private final AuthenticationIdentityRepository identityRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;
    private final AfterCommitEmailDispatcher emailDispatcher;
    private final AuditService auditService;
    private final Clock clock;

    public AccountActionService(
            AccountActionTokenService tokenService,
            AuthenticationIdentityRepository identityRepository,
            PasswordCredentialRepository credentialRepository,
            RefreshSessionRepository refreshSessionRepository,
            PasswordEncoder passwordEncoder,
            SecurityProperties properties,
            AfterCommitEmailDispatcher emailDispatcher,
            AuditService auditService,
            Clock clock) {
        this.tokenService = tokenService;
        this.identityRepository = identityRepository;
        this.credentialRepository = credentialRepository;
        this.refreshSessionRepository = refreshSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.emailDispatcher = emailDispatcher;
        this.auditService = auditService;
        this.clock = clock;
    }

    public void scheduleRegistrationVerification(AuthenticationIdentity identity) {
        AccountActionTokenService.IssuedActionToken issuedToken = tokenService.issue(
                identity,
                AccountActionToken.ActionType.EMAIL_VERIFICATION,
                properties.emailVerificationTokenTtl());
        emailDispatcher.scheduleVerification(
                identity.getId(),
                identity.getNormalizedIdentifier(),
                identity.getUser().getDisplayName(),
                issuedToken.value());
    }

    @Transactional
    public void confirmEmail(String rawToken) {
        AccountActionToken token = validTokenOrAudit(
                rawToken,
                AccountActionToken.ActionType.EMAIL_VERIFICATION,
                "AUTH_EMAIL_VERIFICATION_REJECTED");
        AuthenticationIdentity identity = lockedIdentity(token);
        if (!isPendingVerification(identity)) {
            auditRejectedToken("AUTH_EMAIL_VERIFICATION_REJECTED", token.getId());
            throw tokenService.invalidToken();
        }

        identity.activate(clock.instant());
        identity.getUser().activate();
        tokenService.markUsedAndInvalidateSiblings(token);
        auditService.record(
                identity.getUser().getId(),
                "AUTH_EMAIL_VERIFIED",
                "AUTHENTICATION_IDENTITY",
                identity.getId());
    }

    @Transactional
    public void resendVerification(String email) {
        AuthenticationIdentity identity = identityRepository
                .findByEmailForUpdate(
                        AuthenticationIdentity.Type.EMAIL,
                        EmailNormalizer.normalize(email))
                .orElse(null);
        if (!isPendingVerification(identity)) {
            return;
        }
        if (tokenService.isWithinCooldown(
                identity,
                AccountActionToken.ActionType.EMAIL_VERIFICATION,
                properties.emailVerificationResendCooldown())) {
            auditService.recordSystem(
                    "AUTH_EMAIL_VERIFICATION_RESEND_THROTTLED",
                    "AUTHENTICATION_IDENTITY",
                    identity.getId(),
                    AuditLogEntry.Outcome.DENIED);
            return;
        }

        AccountActionTokenService.IssuedActionToken issuedToken = tokenService.issue(
                identity,
                AccountActionToken.ActionType.EMAIL_VERIFICATION,
                properties.emailVerificationTokenTtl());
        auditService.record(
                identity.getUser().getId(),
                "AUTH_EMAIL_VERIFICATION_REQUESTED",
                "AUTHENTICATION_IDENTITY",
                identity.getId());
        emailDispatcher.scheduleVerification(
                identity.getId(),
                identity.getNormalizedIdentifier(),
                identity.getUser().getDisplayName(),
                issuedToken.value());
    }

    @Transactional
    public void requestPasswordReset(String email) {
        AuthenticationIdentity identity = identityRepository
                .findByEmailForUpdate(
                        AuthenticationIdentity.Type.EMAIL,
                        EmailNormalizer.normalize(email))
                .orElse(null);
        if (!isEligibleForPasswordReset(identity)) {
            return;
        }
        if (tokenService.isWithinCooldown(
                identity,
                AccountActionToken.ActionType.PASSWORD_RESET,
                properties.passwordResetRequestCooldown())) {
            auditService.recordSystem(
                    "AUTH_PASSWORD_RESET_REQUEST_THROTTLED",
                    "AUTHENTICATION_IDENTITY",
                    identity.getId(),
                    AuditLogEntry.Outcome.DENIED);
            return;
        }

        AccountActionTokenService.IssuedActionToken issuedToken = tokenService.issue(
                identity,
                AccountActionToken.ActionType.PASSWORD_RESET,
                properties.passwordResetTokenTtl());
        auditService.record(
                identity.getUser().getId(),
                "AUTH_PASSWORD_RESET_REQUESTED",
                "AUTHENTICATION_IDENTITY",
                identity.getId());
        emailDispatcher.schedulePasswordReset(
                identity.getId(),
                identity.getNormalizedIdentifier(),
                identity.getUser().getDisplayName(),
                issuedToken.value());
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        AccountActionToken token = validTokenOrAudit(
                rawToken,
                AccountActionToken.ActionType.PASSWORD_RESET,
                "AUTH_PASSWORD_RESET_REJECTED");
        AuthenticationIdentity identity = lockedIdentity(token);
        if (!isEligibleForPasswordReset(identity)) {
            auditRejectedToken("AUTH_PASSWORD_RESET_REJECTED", token.getId());
            throw tokenService.invalidToken();
        }

        PasswordCredential credential = credentialRepository.findById(identity.getId()).orElse(null);
        if (credential == null) {
            auditRejectedToken("AUTH_PASSWORD_RESET_REJECTED", token.getId());
            throw tokenService.invalidToken();
        }
        credential.changePassword(
                passwordEncoder.encode(newPassword),
                properties.bcryptStrength(),
                clock.instant());
        tokenService.markUsedAndInvalidateSiblings(token);
        refreshSessionRepository.revokeAllForIdentity(
                identity.getId(),
                clock.instant(),
                "PASSWORD_RESET");
        auditService.record(
                identity.getUser().getId(),
                "AUTH_PASSWORD_RESET",
                "AUTHENTICATION_IDENTITY",
                identity.getId());
    }

    private AccountActionToken validTokenOrAudit(
            String rawToken,
            AccountActionToken.ActionType actionType,
            String rejectedAuditAction) {
        try {
            return tokenService.lockValid(rawToken, actionType);
        } catch (ApiException exception) {
            auditRejectedToken(rejectedAuditAction, null);
            throw exception;
        }
    }

    private void auditRejectedToken(String action, UUID tokenId) {
        auditService.recordSystemInNewTransaction(
                action,
                "ACCOUNT_ACTION_TOKEN",
                tokenId,
                AuditLogEntry.Outcome.DENIED);
    }

    private AuthenticationIdentity lockedIdentity(AccountActionToken token) {
        return identityRepository.findByIdForUpdate(token.getAuthenticationIdentity().getId())
                .orElseThrow(tokenService::invalidToken);
    }

    private boolean isPendingVerification(AuthenticationIdentity identity) {
        return identity != null
                && identity.getStatus() == AuthenticationIdentity.Status.PENDING_VERIFICATION
                && identity.getUser().getStatus() == AppUser.Status.PENDING_VERIFICATION;
    }

    private boolean isEligibleForPasswordReset(AuthenticationIdentity identity) {
        return identity != null
                && identity.getStatus() == AuthenticationIdentity.Status.ACTIVE
                && identity.getVerifiedAt() != null
                && identity.getUser().getStatus() == AppUser.Status.ACTIVE;
    }
}
