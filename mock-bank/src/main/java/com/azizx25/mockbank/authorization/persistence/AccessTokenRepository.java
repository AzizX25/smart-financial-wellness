package com.azizx25.mockbank.authorization.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessTokenRepository extends JpaRepository<AccessToken, UUID> {

    Optional<AccessToken> findByTokenHash(String tokenHash);

    Optional<AccessToken> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, OffsetDateTime asOf);

    List<AccessToken> findByBankConsent_IdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID bankConsentId,
            OffsetDateTime asOf);
}