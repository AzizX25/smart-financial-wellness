package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategorizationReviewCandidateRepository
        extends JpaRepository<CategorizationReviewCandidate, UUID> {

    List<CategorizationReviewCandidate> findByReview_IdInOrderByReview_IdAscCandidateRankAsc(
            Collection<UUID> reviewIds);

    void deleteByReview_Id(UUID reviewId);
}
