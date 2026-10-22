package com.azizx25.smartfinancialwellness.goal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface FinancialGoalRepository extends Repository<FinancialGoal, UUID> {

    <S extends FinancialGoal> S save(S goal);

    void flush();

    Optional<FinancialGoal> findByIdAndUser_Id(UUID goalId, UUID userId);

    List<FinancialGoal> findByUser_IdAndCurrencyOrderByCreatedAtAscIdAsc(UUID userId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select goal from FinancialGoal goal
            where goal.id = :goalId and goal.user.id = :userId
            """)
    Optional<FinancialGoal> findOwnedForUpdate(
            @Param("goalId") UUID goalId,
            @Param("userId") UUID userId);
}
