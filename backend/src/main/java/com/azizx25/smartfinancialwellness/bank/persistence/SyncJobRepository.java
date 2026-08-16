package com.azizx25.smartfinancialwellness.bank.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {

    boolean existsByBankConnection_IdAndStatusIn(
            UUID connectionId,
            Collection<SyncJob.Status> statuses);

    Optional<SyncJob> findTopByBankConnection_IdOrderByCreatedAtDesc(UUID connectionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SyncJob> findWithLockById(UUID id);
}
