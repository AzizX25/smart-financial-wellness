package com.azizx25.smartfinancialwellness.salary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.salary.persistence.SalaryHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalSalaryRecommendationService {

    public static final String METHOD = "HISTORICAL_MONTHLY_AVERAGE";
    public static final String METHOD_VERSION = "1";

    private static final int MAX_HISTORY_MONTHS = 3;

    private final CategoryRepository categoryRepository;
    private final SalaryHistoryRepository historyRepository;

    public HistoricalSalaryRecommendationService(
            CategoryRepository categoryRepository,
            SalaryHistoryRepository historyRepository) {
        this.categoryRepository = categoryRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public Recommendation recommend(UUID userId, YearMonth planMonth, String planCurrency) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(planMonth, "planMonth is required");
        String currency = MonetaryAmounts.requireCurrency(planCurrency);
        LocalDate periodStart = planMonth.minusMonths(MAX_HISTORY_MONTHS).atDay(1);
        LocalDate periodEndExclusive = planMonth.atDay(1);

        List<Category> eligibleCategories = categoryRepository.findVisibleActive(userId).stream()
                .filter(category -> category.getNature() == Category.Nature.EXPENSE)
                .toList();
        List<SalaryHistoryRepository.HistoricalSpendingRow> rows =
                historyRepository.summarizeEligibleExpenseSpending(
                        userId, currency, periodStart, periodEndExclusive);

        Set<LocalDate> usableMonths = new LinkedHashSet<>();
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        rows.forEach(row -> {
            usableMonths.add(row.getSpendingMonth());
            totals.merge(row.getCategoryId(), row.getTotalAmount(), BigDecimal::add);
        });

        int usableMonthCount = usableMonths.size();
        FallbackReason fallbackReason = usableMonthCount == 0
                ? FallbackReason.NO_USABLE_HISTORY
                : FallbackReason.NONE;
        List<CategoryRecommendation> recommendations = eligibleCategories.stream()
                .map(category -> new CategoryRecommendation(
                        category.getId(),
                        category.getLabel(),
                        average(totals.getOrDefault(category.getId(), BigDecimal.ZERO), usableMonthCount)))
                .toList();

        return new Recommendation(
                periodStart,
                periodEndExclusive.minusDays(1),
                usableMonthCount,
                METHOD,
                METHOD_VERSION,
                fallbackReason,
                recommendations);
    }

    private BigDecimal average(BigDecimal total, int monthCount) {
        if (monthCount == 0) {
            return BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);
        }
        return total.divide(BigDecimal.valueOf(monthCount), MonetaryAmounts.SCALE, RoundingMode.HALF_EVEN);
    }

    public enum FallbackReason { NONE, NO_USABLE_HISTORY }

    public record CategoryRecommendation(UUID categoryId, String categoryName, BigDecimal recommendedAmount) {
    }

    public record Recommendation(
            LocalDate periodStart,
            LocalDate periodEndInclusive,
            int usableMonthCount,
            String method,
            String methodVersion,
            FallbackReason fallbackReason,
            List<CategoryRecommendation> categories) {

        public Recommendation {
            categories = List.copyOf(categories);
        }
    }
}
