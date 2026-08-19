package com.azizx25.smartfinancialwellness.forecast.persistence;

import java.util.Optional;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastResult.MlStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForecastResultRepository extends JpaRepository<ForecastResult, UUID> {
    Optional<ForecastResult> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<ForecastResult> findByUser_IdAndCurrencyAndCanonicalInputFingerprintAndMlStatus(
            UUID userId, String currency, String fingerprint, MlStatus mlStatus);

    Page<ForecastResult> findByUser_IdAndCurrencyOrderByCreatedAtDescIdDesc(
            UUID userId, String currency, Pageable pageable);
}
