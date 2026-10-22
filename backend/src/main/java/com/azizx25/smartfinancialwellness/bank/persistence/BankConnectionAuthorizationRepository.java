package com.azizx25.smartfinancialwellness.bank.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankConnectionAuthorizationRepository
        extends JpaRepository<BankConnectionAuthorization, UUID> {

    Optional<BankConnectionAuthorization> findByBankConnection_IdAndStatus(
            UUID bankConnectionId,
            BankConnectionAuthorization.Status status);

    Optional<BankConnectionAuthorization> findTopByBankConnection_IdOrderByConsentedAtDesc(
            UUID bankConnectionId);
}
