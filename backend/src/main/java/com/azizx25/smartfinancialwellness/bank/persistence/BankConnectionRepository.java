package com.azizx25.smartfinancialwellness.bank.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankConnectionRepository extends JpaRepository<BankConnection, UUID> {

    Optional<BankConnection> findByUser_IdAndProviderCodeAndStatusIn(
            UUID userId,
            String providerCode,
            Collection<BankConnection.Status> statuses);

    Optional<BankConnection> findByProviderCodeAndExternalCustomerIdAndStatusIn(
            String providerCode,
            String customerId,
            Collection<BankConnection.Status> statuses);

    Optional<BankConnection> findTopByUser_IdAndProviderCodeOrderByCreatedAtDesc(
            UUID userId,
            String providerCode);

    Optional<BankConnection> findByIdAndUser_Id(UUID connectionId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from BankConnection connection "
            + "where connection.id = :connectionId and connection.user.id = :userId")
    Optional<BankConnection> findOwnedByIdForUpdate(
            @Param("connectionId") UUID connectionId,
            @Param("userId") UUID userId);
}
