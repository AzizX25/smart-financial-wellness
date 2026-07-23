package com.azizx25.smartfinancialwellness.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.auth.persistence.RefreshSession;
import com.azizx25.smartfinancialwellness.auth.persistence.RefreshSessionRepository;
import com.azizx25.smartfinancialwellness.config.SecurityProperties;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final String REUSE_REASON = "TOKEN_REUSE_DETECTED";

    private final RefreshSessionRepository repository;
    private final SecurityProperties properties;
    private final Clock clock;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshSessionRepository repository,
            SecurityProperties properties,
            Clock clock,
            AuditService auditService) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional
    public IssuedRefreshToken create(AuthenticationIdentity identity) {
        return create(identity, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public IssuedRefreshToken rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new RefreshTokenException("A valid refresh token is required");
        }
        RefreshSession current = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new RefreshTokenException("A valid refresh token is required"));
        Instant now = clock.instant();

        if (current.getLastUsedAt() != null || current.getReplacedBySession() != null) {
            repository.markReuseDetected(current.getId(), now);
            repository.revokeFamily(current.getFamilyId(), now, REUSE_REASON);
            auditService.record(
                    current.getAuthenticationIdentity().getUser().getId(),
                    "AUTH_REFRESH_REUSE_DETECTED",
                    "REFRESH_SESSION",
                    current.getId());
            throw new RefreshTokenReuseException();
        }
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            throw new RefreshTokenException("A valid refresh token is required");
        }
        ensureAuthenticationAllowed(current.getAuthenticationIdentity());

        IssuedRefreshToken successor = create(
                current.getAuthenticationIdentity(),
                current.getFamilyId());
        current.rotateTo(successor.session(), now);
        repository.saveAndFlush(current);
        return successor;
    }

    @Transactional
    public void revoke(String rawToken, UUID authenticationIdentityId) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken))
                .filter(session -> session.getAuthenticationIdentity().getId().equals(authenticationIdentityId))
                .ifPresent(session -> {
                    session.revoke(clock.instant(), "LOGOUT");
                    repository.save(session);
                });
    }

    private IssuedRefreshToken create(AuthenticationIdentity identity, UUID familyId) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = clock.instant().plus(properties.refreshTokenTtl());
        RefreshSession session = new RefreshSession(
                UUID.randomUUID(),
                identity,
                familyId,
                hash(rawToken),
                expiresAt);
        repository.saveAndFlush(session);
        return new IssuedRefreshToken(rawToken, expiresAt, session);
    }

    private void ensureAuthenticationAllowed(AuthenticationIdentity identity) {
        AppUser.Status userStatus = identity.getUser().getStatus();
        if (identity.getStatus() == AuthenticationIdentity.Status.DISABLED
                || userStatus == AppUser.Status.SUSPENDED
                || userStatus == AppUser.Status.DELETION_PENDING
                || userStatus == AppUser.Status.DELETED) {
            throw new RefreshTokenException("A valid refresh token is required");
        }
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record IssuedRefreshToken(String value, Instant expiresAt, RefreshSession session) {

        public AuthenticationIdentity identity() {
            return session.getAuthenticationIdentity();
        }
    }
}
