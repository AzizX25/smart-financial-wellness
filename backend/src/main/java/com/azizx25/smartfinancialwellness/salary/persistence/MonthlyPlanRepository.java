package com.azizx25.smartfinancialwellness.salary.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonthlyPlanRepository extends JpaRepository<MonthlyPlan, UUID> {

    Optional<MonthlyPlan> findByUser_IdAndPeriodMonthAndCurrency(
            UUID userId,
            LocalDate periodMonth,
            String currency);

    List<MonthlyPlan> findByUser_IdAndCurrencyOrderByPeriodMonthDesc(UUID userId, String currency);

    Page<MonthlyPlan> findByUser_IdAndCurrencyOrderByPeriodMonthDesc(
            UUID userId,
            String currency,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select plan from MonthlyPlan plan
            where plan.user.id = :userId
              and plan.periodMonth = :periodMonth
              and plan.currency = :currency
            """)
    Optional<MonthlyPlan> findOwnedForUpdate(
            @Param("userId") UUID userId,
            @Param("periodMonth") LocalDate periodMonth,
            @Param("currency") String currency);
}
