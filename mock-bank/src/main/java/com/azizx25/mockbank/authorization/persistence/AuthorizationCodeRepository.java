package com.azizx25.mockbank.authorization.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationCodeRepository extends JpaRepository<AuthorizationCode, UUID> {

    void deleteByBankConsent_Id(UUID bankConsentId);

    Optional<AuthorizationCode> findByCodeHash(String codeHash);

    Optional<AuthorizationCode> findByCodeHashAndUsedAtIsNullAndExpiresAtAfter(String codeHash, OffsetDateTime asOf);
}
