package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategorizationReviewRepository extends JpaRepository<CategorizationReview, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select review from CategorizationReview review
            where review.transaction.id = :transactionId
              and review.user.id = :userId
              and review.resolvedAt is null
            """)
    Optional<CategorizationReview> findOwnedOpenForUpdate(
            @Param("transactionId") UUID transactionId,
            @Param("userId") UUID userId);

    @Query("""
            select review from CategorizationReview review
            where review.transaction.id in :transactionIds
              and review.user.id = :userId
              and review.resolvedAt is null
            """)
    List<CategorizationReview> findOwnedOpen(
            @Param("transactionIds") Collection<UUID> transactionIds,
            @Param("userId") UUID userId);
}
