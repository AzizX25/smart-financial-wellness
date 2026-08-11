package com.azizx25.smartfinancialwellness.salary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.salary.HistoricalSalaryRecommendationService.Recommendation;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.AllocationOverrideRequest;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.AllocationProposalResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.AllocationResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.IncomeRequest;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.IncomeResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.RecommendationMetadataResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanPageResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanProposalResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanPutRequest;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanResponse;
import com.azizx25.smartfinancialwellness.salary.persistence.BudgetAllocation;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlanRepository;
import com.azizx25.smartfinancialwellness.salary.persistence.PlannedIncome;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryPlanService {

    private final MonthlyPlanRepository planRepository;
    private final CategoryRepository categoryRepository;
    private final AppUserRepository userRepository;
    private final HistoricalSalaryRecommendationService recommendationService;
    private final SalaryPlanCalculator calculator;
    private final AuditService auditService;
    private final Clock clock;

    public SalaryPlanService(
            MonthlyPlanRepository planRepository,
            CategoryRepository categoryRepository,
            AppUserRepository userRepository,
            HistoricalSalaryRecommendationService recommendationService,
            SalaryPlanCalculator calculator,
            AuditService auditService,
            Clock clock) {
        this.planRepository = planRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.recommendationService = recommendationService;
        this.calculator = calculator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SalaryPlanResponse get(UUID userId, YearMonth month, String currency) {
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        MonthlyPlan plan = planRepository.findByUser_IdAndPeriodMonthAndCurrency(
                        userId, month.atDay(1), normalizedCurrency)
                .orElseThrow(SalaryPlanException::notFound);
        return response(plan);
    }

    @Transactional(readOnly = true)
    public SalaryPlanPageResponse list(UUID userId, String currency, int page, int size) {
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        Page<MonthlyPlan> plans = planRepository.findByUser_IdAndCurrencyOrderByPeriodMonthDesc(
                userId, normalizedCurrency, PageRequest.of(page, size));
        List<SalaryPlanResponse> items = plans.getContent().stream()
                .map(this::response)
                .toList();
        return new SalaryPlanPageResponse(
                items, plans.getNumber(), plans.getSize(), plans.getTotalElements(), plans.getTotalPages());
    }

    @Transactional(readOnly = true)
    public SalaryPlanProposalResponse proposals(UUID userId, YearMonth month, String currency) {
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        Recommendation recommendation = recommendationService.recommend(userId, month, normalizedCurrency);
        List<AllocationProposalResponse> allocations = recommendation.categories().stream()
                .map(item -> new AllocationProposalResponse(
                        item.categoryId(), item.categoryName(), item.recommendedAmount()))
                .toList();
        BigDecimal total = recommendation.categories().stream()
                .map(HistoricalSalaryRecommendationService.CategoryRecommendation::recommendedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SalaryPlanProposalResponse(
                month.toString(),
                normalizedCurrency,
                allocations,
                MonetaryAmounts.calculated(total),
                metadata(recommendation));
    }

    @Transactional
    public SalaryPlanResponse replace(
            UUID userId,
            YearMonth month,
            String currency,
            SalaryPlanPutRequest request) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(month, "month is required");
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        validateRequest(request);
        Instant now = clock.instant();
        Recommendation recommendation = recommendationService.recommend(userId, month, normalizedCurrency);
        Map<UUID, Category> eligibleCategories = eligibleExpenseCategories(userId);
        Map<UUID, BigDecimal> overrides = overrides(request.allocationOverrides(), eligibleCategories.keySet());

        MonthlyPlan plan = planRepository.findOwnedForUpdate(userId, month.atDay(1), normalizedCurrency)
                .orElseGet(() -> new MonthlyPlan(
                        userRepository.getReferenceById(userId), month, normalizedCurrency, now));

        plan.clearIncomes();
        request.incomes().forEach(income -> plan.addIncome(
                income.label().strip(),
                PlannedIncome.IncomeType.OTHER,
                income.expectedAmount(),
                null,
                false,
                now));

        Set<UUID> recommendationCategoryIds = recommendation.categories().stream()
                .map(HistoricalSalaryRecommendationService.CategoryRecommendation::categoryId)
                .collect(Collectors.toSet());
        plan.retainAllocationsFor(recommendationCategoryIds);
        Map<UUID, BudgetAllocation> existingAllocations = plan.getBudgetAllocations().stream()
                .collect(Collectors.toMap(
                        allocation -> allocation.getCategory().getId(), Function.identity()));

        recommendation.categories().forEach(item -> {
            Category category = eligibleCategories.get(item.categoryId());
            if (category == null) {
                throw SalaryPlanException.invalid("A recommended category is no longer eligible");
            }
            BudgetAllocation allocation = existingAllocations.get(item.categoryId());
            if (allocation == null) {
                allocation = plan.addAllocation(
                        category,
                        item.recommendedAmount(),
                        BudgetAllocation.RecommendationOrigin.RULES,
                        now);
            } else {
                allocation.updateRecommendation(
                        item.recommendedAmount(), BudgetAllocation.RecommendationOrigin.RULES, now);
            }
            BigDecimal confirmedAmount = overrides.get(item.categoryId());
            if (confirmedAmount == null) {
                allocation.clearConfirmation(now);
            } else {
                allocation.confirm(confirmedAmount, now);
            }
        });

        SalaryPlanCalculator.Calculation calculation = calculate(plan);
        plan.applyRecommendationCalculation(
                calculation,
                new MonthlyPlan.RecommendationSnapshot(
                        recommendation.method(),
                        recommendation.methodVersion(),
                        recommendation.periodStart(),
                        recommendation.periodEndInclusive(),
                        recommendation.usableMonthCount(),
                        fallbackReason(recommendation)),
                now);
        try {
            planRepository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw SalaryPlanException.conflict();
        }
        auditService.record(userId, "SALARY_PLAN_REPLACED", "MONTHLY_PLAN", plan.getId());
        return response(plan);
    }

    private void validateRequest(SalaryPlanPutRequest request) {
        if (request == null || request.incomes() == null || request.allocationOverrides() == null) {
            throw SalaryPlanException.invalid("Incomes and allocation overrides are required");
        }
        for (IncomeRequest income : request.incomes()) {
            if (income == null || income.label() == null || income.label().isBlank()
                    || income.label().strip().length() > 160) {
                throw SalaryPlanException.invalid("Income labels must contain 1 to 160 characters");
            }
            validateAmount(income.expectedAmount(), "Expected income");
        }
        Set<UUID> categoryIds = new HashSet<>();
        for (AllocationOverrideRequest override : request.allocationOverrides()) {
            if (override == null || override.categoryId() == null) {
                throw SalaryPlanException.invalid("An allocation category is required");
            }
            validateAmount(override.confirmedAmount(), "Confirmed allocation");
            if (!categoryIds.add(override.categoryId())) {
                throw SalaryPlanException.invalid("Duplicate allocation category");
            }
        }
    }

    private void validateAmount(BigDecimal amount, String field) {
        try {
            MonetaryAmounts.requireNonNegativeInput(amount, field);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw SalaryPlanException.invalid(exception.getMessage());
        }
    }

    private Map<UUID, Category> eligibleExpenseCategories(UUID userId) {
        return categoryRepository.findVisibleActive(userId).stream()
                .filter(category -> category.getNature() == Category.Nature.EXPENSE)
                .collect(Collectors.toMap(
                        Category::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private Map<UUID, BigDecimal> overrides(
            List<AllocationOverrideRequest> requestedOverrides,
            Set<UUID> eligibleCategoryIds) {
        Map<UUID, BigDecimal> overrides = new LinkedHashMap<>();
        requestedOverrides.forEach(override -> {
            if (!eligibleCategoryIds.contains(override.categoryId())) {
                throw SalaryPlanException.invalid("Category must be an active, visible expense category");
            }
            overrides.put(override.categoryId(), override.confirmedAmount());
        });
        return overrides;
    }

    private SalaryPlanCalculator.Calculation calculate(MonthlyPlan plan) {
        return calculator.calculate(
                plan.getPlannedIncomes().stream().map(PlannedIncome::getExpectedAmount).toList(),
                plan.getBudgetAllocations().stream()
                        .map(allocation -> new SalaryPlanCalculator.AllocationAmount(
                                allocation.getRecommendedAmount(), allocation.getConfirmedAmount()))
                        .toList());
    }

    private SalaryPlanResponse response(MonthlyPlan plan) {
        SalaryPlanCalculator.Calculation calculation = calculate(plan);
        List<IncomeResponse> incomes = plan.getPlannedIncomes().stream()
                .map(income -> new IncomeResponse(income.getLabel(), income.getExpectedAmount()))
                .toList();
        List<AllocationResponse> allocations = plan.getBudgetAllocations().stream()
                .sorted(Comparator.comparing(
                                (BudgetAllocation allocation) -> allocation.getCategory().getLabel(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(allocation -> allocation.getCategory().getId()))
                .map(allocation -> new AllocationResponse(
                        allocation.getCategory().getId(),
                        allocation.getCategory().getLabel(),
                        allocation.getRecommendedAmount(),
                        allocation.getConfirmedAmount(),
                        allocation.getEffectiveAmount()))
                .toList();
        return new SalaryPlanResponse(
                plan.getPeriodMonth().toString(),
                plan.getCurrency(),
                incomes,
                allocations,
                calculation.totalIncome(),
                calculation.totalExpenses(),
                calculation.savingCapacity(),
                calculation.deficit(),
                metadata(plan),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getVersion());
    }

    private RecommendationMetadataResponse metadata(Recommendation recommendation) {
        return new RecommendationMetadataResponse(
                true,
                recommendation.periodStart(),
                recommendation.periodEndInclusive(),
                recommendation.usableMonthCount(),
                recommendation.method(),
                recommendation.methodVersion(),
                fallbackReason(recommendation));
    }

    private RecommendationMetadataResponse metadata(MonthlyPlan plan) {
        if (!plan.isRecommendationMetadataAvailable()) {
            return new RecommendationMetadataResponse(false, null, null, null, null, null, null);
        }
        return new RecommendationMetadataResponse(
                true,
                plan.getRecommendationPeriodStart(),
                plan.getRecommendationPeriodEnd(),
                plan.getRecommendationUsableMonthCount(),
                plan.getCalculationMethod(),
                plan.getMethodVersion(),
                plan.getRecommendationFallbackReason());
    }

    private String fallbackReason(Recommendation recommendation) {
        return recommendation.fallbackReason() == HistoricalSalaryRecommendationService.FallbackReason.NONE
                ? null
                : recommendation.fallbackReason().name();
    }
}
