package com.azizx25.smartfinancialwellness.score.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WellnessEvaluationRepository extends JpaRepository<WellnessEvaluation, UUID> {

    Optional<WellnessEvaluation> findByUser_IdAndCurrencyAndAsOfDateAndMethodVersionAndInputFingerprint(
            UUID userId, String currency, LocalDate asOfDate, String methodVersion, String inputFingerprint);

    Optional<WellnessEvaluation> findByIdAndUser_IdAndMethodVersion(
            UUID scoreId, UUID userId, String methodVersion);

    Page<WellnessEvaluation> findByUser_IdAndCurrencyAndMethodVersionOrderByCreatedAtDescIdDesc(
            UUID userId, String currency, String methodVersion, Pageable pageable);
}
