package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CategoryPredictionRepository extends JpaRepository<CategoryPrediction, UUID> {

    @Query("""
            select prediction from CategoryPrediction prediction
            join fetch prediction.transaction transaction
            join fetch prediction.category
            where transaction.user.id = :userId
              and transaction.categorySource = 'UNCATEGORIZED'
              and prediction.reviewStatus = com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction.ReviewStatus.PENDING
            order by prediction.producedAt desc, prediction.id
            """)
    List<CategoryPrediction> findOwnedPending(@Param("userId") UUID userId);

    boolean existsByTransaction_IdAndReviewStatus(
            UUID transactionId, CategoryPrediction.ReviewStatus reviewStatus);

    Optional<CategoryPrediction> findByTransaction_IdAndReviewStatus(
            UUID transactionId, CategoryPrediction.ReviewStatus reviewStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select prediction from CategoryPrediction prediction
            join fetch prediction.transaction transaction
            join fetch prediction.category
            where prediction.id = :predictionId
              and transaction.id = :transactionId
              and transaction.user.id = :userId
              and prediction.reviewStatus = com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction.ReviewStatus.PENDING
            """)
    Optional<CategoryPrediction> findOwnedPendingForUpdate(
            @Param("predictionId") UUID predictionId,
            @Param("transactionId") UUID transactionId,
            @Param("userId") UUID userId);
}
