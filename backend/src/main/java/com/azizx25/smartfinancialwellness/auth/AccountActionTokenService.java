package com.azizx25.smartfinancialwellness.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.persistence.AccountActionToken;
import com.azizx25.smartfinancialwellness.auth.persistence.AccountActionTokenRepository;
import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountActionTokenService {

    private static final int TOKEN_BYTES = 32;

    private final AccountActionTokenRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountActionTokenService(AccountActionTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IssuedActionToken issue(
            AuthenticationIdentity identity,
            AccountActionToken.ActionType actionType,
            Duration timeToLive) {
        Instant now = clock.instant();
        repository.invalidateUnused(identity.getId(), actionType, now);

        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        AccountActionToken token = new AccountActionToken(
                UUID.randomUUID(),
                identity,
                actionType,
                hash(rawToken),
                now.plus(timeToLive),
                now);
        repository.saveAndFlush(token);
        return new IssuedActionToken(rawToken, token);
    }

    public AccountActionToken lockValid(String rawToken, AccountActionToken.ActionType actionType) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            throw invalidToken();
        }
        Instant now = clock.instant();
        AccountActionToken token = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(this::invalidToken);
        if (!token.isAvailableFor(actionType, now)) {
            throw invalidToken();
        }
        return token;
    }

    public boolean isWithinCooldown(
            AuthenticationIdentity identity,
            AccountActionToken.ActionType actionType,
            Duration cooldown) {
        Instant now = clock.instant();
        return repository.findLatestCreatedAt(identity.getId(), actionType)
                .map(createdAt -> createdAt.plus(cooldown).isAfter(now))
                .orElse(false);
    }

    public void markUsedAndInvalidateSiblings(AccountActionToken token) {
        Instant now = clock.instant();
        token.markUsed(now);
        repository.saveAndFlush(token);
        repository.invalidateUnused(
                token.getAuthenticationIdentity().getId(),
                token.getActionType(),
                now);
    }

    public ApiException invalidToken() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "The account action token is invalid or has expired");
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record IssuedActionToken(String value, AccountActionToken token) {

        @Override
        public String toString() {
            return "IssuedActionToken[value=redacted]";
        }
    }
}
