package com.azizx25.smartfinancialwellness.bank.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, UUID> {

    boolean existsByFinancialAccount_IdAndObservedAtAndSourceType(
            UUID accountId,
            Instant observedAt,
            BalanceSnapshot.SourceType sourceType);

    @Query("""
            select snapshot from BalanceSnapshot snapshot
            where snapshot.financialAccount.id in :accountIds
              and snapshot.observedAt < :futureBoundary
            order by snapshot.financialAccount.id,
                     snapshot.observedAt desc,
                     snapshot.createdAt desc,
                     snapshot.id desc
            """)
    List<BalanceSnapshot> findUsableSnapshots(
            @Param("accountIds") Collection<UUID> accountIds,
            @Param("futureBoundary") Instant futureBoundary);
}
