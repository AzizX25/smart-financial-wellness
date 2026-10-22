package com.azizx25.smartfinancialwellness.goal.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface GoalAllocationRepository extends Repository<GoalAllocation, UUID> {

    <S extends GoalAllocation> S save(S allocation);

    void flush();

    @Query("""
            select allocation from GoalAllocation allocation
            where allocation.monthlyPlan.id = :planId
              and allocation.monthlyPlan.user.id = :userId
            order by allocation.financialGoal.id, allocation.id
            """)
    List<GoalAllocation> findByOwnedMonthlyPlan(
            @Param("planId") UUID planId,
            @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select allocation from GoalAllocation allocation
            where allocation.monthlyPlan.id = :planId
              and allocation.monthlyPlan.user.id = :userId
            order by allocation.financialGoal.id, allocation.id
            """)
    List<GoalAllocation> findByOwnedMonthlyPlanForUpdate(
            @Param("planId") UUID planId,
            @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select allocation.monthlyPlan from GoalAllocation allocation
            where allocation.financialGoal.id = :goalId
              and allocation.financialGoal.user.id = :userId
              and allocation.monthlyPlan.periodMonth >= :firstMonth
              and allocation.status <> com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation.Status.CANCELLED
            order by allocation.monthlyPlan.periodMonth, allocation.monthlyPlan.id
            """)
    List<MonthlyPlan> lockAffectedMonthlyPlans(
            @Param("goalId") UUID goalId,
            @Param("userId") UUID userId,
            @Param("firstMonth") LocalDate firstMonth);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select allocation from GoalAllocation allocation
            where allocation.financialGoal.id = :goalId
              and allocation.financialGoal.user.id = :userId
              and allocation.monthlyPlan.periodMonth >= :firstMonth
              and allocation.status <> com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation.Status.CANCELLED
            order by allocation.monthlyPlan.periodMonth, allocation.monthlyPlan.id, allocation.id
            """)
    List<GoalAllocation> findCurrentAndFutureForUpdate(
            @Param("goalId") UUID goalId,
            @Param("userId") UUID userId,
            @Param("firstMonth") LocalDate firstMonth);
}
