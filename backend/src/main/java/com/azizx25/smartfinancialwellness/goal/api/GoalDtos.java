package com.azizx25.smartfinancialwellness.goal.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoal;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContribution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class GoalDtos {

    private GoalDtos() {
    }

    public record GoalWriteRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotNull FinancialGoal.GoalType type,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @DecimalMin(value = "0", inclusive = false)
                    @Digits(integer = 15, fraction = 4) BigDecimal targetAmount,
            @NotNull @DecimalMin("0")
                    @Digits(integer = 15, fraction = 4) BigDecimal initialAmount,
            LocalDate targetDate,
            @NotNull FinancialGoal.Priority priority) {
    }

    public record GoalStatusRequest(@NotNull FinancialGoal.Status status) {
    }

    public record GoalContributionRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false)
                    @Digits(integer = 15, fraction = 4) BigDecimal amount,
            LocalDate contributionDate) {
    }

    public record GoalAllocationReplaceRequest(
            @NotNull List<@NotNull @Valid GoalAllocationRequest> allocations) {
    }

    public record GoalAllocationRequest(
            @NotNull UUID goalId,
            @NotNull @DecimalMin(value = "0", inclusive = false)
                    @Digits(integer = 15, fraction = 4) BigDecimal amount) {
    }

    public record GoalSimulationRequest(
            @NotNull @DecimalMin("0")
                    @Digits(integer = 15, fraction = 4) BigDecimal monthlyContribution) {
    }

    public record GoalResponse(
            UUID id,
            String name,
            String description,
            FinancialGoal.GoalType type,
            String currency,
            BigDecimal targetAmount,
            BigDecimal initialAmount,
            BigDecimal contributionTotal,
            BigDecimal currentSaved,
            BigDecimal remainingAmount,
            BigDecimal progressPercentage,
            LocalDate targetDate,
            boolean overdue,
            FinancialGoal.Priority priority,
            FinancialGoal.Status status,
            Instant createdAt,
            Instant updatedAt,
            int version) {
    }

    public record GoalContributionResponse(
            UUID id,
            BigDecimal amount,
            LocalDate contributionDate,
            GoalContribution.Source source,
            Instant createdAt) {
    }

    public record GoalAllocationOverviewResponse(
            String month,
            String currency,
            boolean capacityAvailable,
            BigDecimal salaryPlanSavingCapacity,
            BigDecimal allocatableCapacity,
            BigDecimal totalConfirmedAllocation,
            BigDecimal remainingUnallocatedCapacity,
            List<GoalAllocationItemResponse> allocations) {
    }

    public record GoalAllocationItemResponse(
            UUID goalId,
            String goalName,
            FinancialGoal.Status goalStatus,
            FinancialGoal.Priority priority,
            BigDecimal allocatedAmount,
            GoalAllocation.Status allocationStatus,
            GoalProjectionResponse projection) {
    }

    public record GoalProjectionResponse(
            BigDecimal remainingGoalAmount,
            Integer monthsToTarget,
            LocalDate estimatedAchievementDate,
            boolean reachable,
            boolean guaranteed) {
    }

    public record GoalSimulationResponse(
            UUID goalId,
            String currency,
            LocalDate calculationDate,
            BigDecimal currentSaved,
            BigDecimal remainingAmount,
            BigDecimal simulatedMonthlyContribution,
            Integer monthsToTarget,
            LocalDate estimatedAchievementDate,
            boolean reachable,
            LocalDate targetDate,
            Boolean meetsTargetDate,
            String method,
            String methodVersion) {
    }
}
