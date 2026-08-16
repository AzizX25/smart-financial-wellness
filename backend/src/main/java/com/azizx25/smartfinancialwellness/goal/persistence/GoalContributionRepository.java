package com.azizx25.smartfinancialwellness.goal.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface GoalContributionRepository extends Repository<GoalContribution, UUID> {

    <S extends GoalContribution> S save(S contribution);

    void delete(GoalContribution contribution);

    void flush();

    List<GoalContribution> findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateAscCreatedAtAsc(
            UUID goalId,
            UUID userId);

    List<GoalContribution> findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateDescCreatedAtDescIdDesc(
            UUID goalId,
            UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select contribution from GoalContribution contribution
            where contribution.id = :contributionId
              and contribution.financialGoal.id = :goalId
              and contribution.financialGoal.user.id = :userId
            """)
    java.util.Optional<GoalContribution> findOwnedForUpdate(
            @Param("contributionId") UUID contributionId,
            @Param("goalId") UUID goalId,
            @Param("userId") UUID userId);
}
