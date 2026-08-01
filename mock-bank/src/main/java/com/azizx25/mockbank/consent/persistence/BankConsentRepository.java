package com.azizx25.mockbank.consent.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankConsentRepository extends JpaRepository<BankConsent, UUID> {

    @EntityGraph(attributePaths = {"bankCustomer", "oauthClient"})
    @Query("select consent from BankConsent consent where consent.id = :id")
    Optional<BankConsent> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"bankCustomer", "oauthClient"})
    @Query("""
            select consent from BankConsent consent
            where consent.bankCustomer.externalCustomerId = :externalCustomerId
              and consent.oauthClient.clientIdentifier = :clientIdentifier
            """)
    Optional<BankConsent> findDetailedByCustomerExternalCustomerIdAndClientIdentifier(
            @Param("externalCustomerId") String externalCustomerId,
            @Param("clientIdentifier") String clientIdentifier);

    @EntityGraph(attributePaths = {"bankCustomer", "oauthClient"})
    @Query("""
            select consent from BankConsent consent
            where consent.bankCustomer.externalCustomerId = :externalCustomerId
              and consent.oauthClient.clientIdentifier = :clientIdentifier
              and consent.status = 'ACTIVE'
              and consent.revokedAt is null
              and consent.expiresAt > :asOf
            """)
    Optional<BankConsent> findActiveDetailedByCustomerExternalCustomerIdAndClientIdentifier(
            @Param("externalCustomerId") String externalCustomerId,
            @Param("clientIdentifier") String clientIdentifier,
            @Param("asOf") OffsetDateTime asOf);
}