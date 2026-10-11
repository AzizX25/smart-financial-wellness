package com.azizx25.smartfinancialwellness.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator.GoalProgress;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoal;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoalRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContribution;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContributionRepository;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Calculation;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Input;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.MonthlyCashFlow;
import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.ScorePageResponse;
import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.ScoreResponse;
import com.azizx25.smartfinancialwellness.score.persistence.FinancialScoreInputRepository;
import com.azizx25.smartfinancialwellness.score.persistence.FinancialScoreInputRepository.CompletedPlanRow;
import com.azizx25.smartfinancialwellness.score.persistence.FinancialScoreInputRepository.EligibleTransactionRow;
import com.azizx25.smartfinancialwellness.score.persistence.WellnessEvaluation;
import com.azizx25.smartfinancialwellness.score.persistence.WellnessEvaluationRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import com.azizx25.smartfinancialwellness.user.persistence.InterfacePreferenceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialScoreService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final String FALLBACK_TIME_ZONE = "Africa/Tunis";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.0000");

    private final FinancialWellnessScoreCalculator calculator;
    private final FinancialScoreInputRepository inputRepository;
    private final WellnessEvaluationRepository evaluationRepository;
    private final FinancialGoalRepository goalRepository;
    private final GoalContributionRepository contributionRepository;
    private final GoalCalculator goalCalculator;
    private final InterfacePreferenceRepository preferenceRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public FinancialScoreService(
            FinancialWellnessScoreCalculator calculator,
            FinancialScoreInputRepository inputRepository,
            WellnessEvaluationRepository evaluationRepository,
            FinancialGoalRepository goalRepository,
            GoalContributionRepository contributionRepository,
            GoalCalculator goalCalculator,
            InterfacePreferenceRepository preferenceRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock) {
        this.calculator = calculator;
        this.inputRepository = inputRepository;
        this.evaluationRepository = evaluationRepository;
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.goalCalculator = goalCalculator;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ScoreResponse calculateCurrent(UUID userId, String currency) {
        requireCurrency(currency);
        ZoneId zone = userTimeZone(userId);
        LocalDate asOfDate = LocalDate.now(clock.withZone(zone));
        LocalDate historyEnd = YearMonth.from(asOfDate).minusMonths(1).atEndOfMonth();
        LocalDate historyStart = YearMonth.from(asOfDate).minusMonths(6).atDay(1);

        List<EligibleTransactionRow> transactions = inputRepository.findEligibleTransactions(
                userId, currency, historyStart, historyEnd.plusDays(1));
        List<CompletedPlanRow> plans = inputRepository.findCompletedPlanBudgets(
                userId, currency, historyStart, historyEnd.plusDays(1));
        List<FinancialGoal> goals = goalRepository
                .findByUser_IdAndCurrencyOrderByCreatedAtAscIdAsc(userId, currency).stream()
                .filter(goal -> goal.getStatus() == FinancialGoal.Status.ACTIVE
                        || goal.getStatus() == FinancialGoal.Status.ACHIEVED)
                .toList();

        Map<YearMonth, MonthlyAmounts> monthly = monthlyAmounts(historyStart, transactions);
        List<BigDecimal> salaryRatios = salaryRatios(plans, monthly);
        GoalInputs goalInputs = goalInputs(userId, goals, zone, asOfDate);
        Calculation calculation = calculator.calculate(new Input(
                monthly.entrySet().stream()
                        .map(entry -> new MonthlyCashFlow(
                                entry.getKey(), entry.getValue().income, entry.getValue().expense))
                        .toList(),
                Optional.empty(), Optional.empty(), Optional.empty(), salaryRatios,
                goalInputs.scores()));

        if (!calculation.available()) {
            return ScoreResponse.insufficient(currency, asOfDate, zone.getId(),
                    historyStart, historyEnd, FinancialWellnessScoreCalculator.VERSION, calculation);
        }

        String fingerprint = fingerprint(currency, zone, asOfDate, historyStart, historyEnd,
                transactions, plans, goalInputs.canonicalInputs());
        Optional<WellnessEvaluation> existing = evaluationRepository
                .findByUser_IdAndCurrencyAndAsOfDateAndMethodVersionAndInputFingerprint(
                        userId, currency, asOfDate, FinancialWellnessScoreCalculator.VERSION, fingerprint);
        if (existing.isPresent()) {
            return ScoreResponse.from(existing.orElseThrow());
        }

        var now = clock.instant();
        WellnessEvaluation evaluation = new WellnessEvaluation(
                userRepository.getReferenceById(userId), currency, asOfDate, zone.getId(),
                historyStart, historyEnd, FinancialWellnessScoreCalculator.VERSION,
                fingerprint, calculation, now);
        evaluationRepository.saveAndFlush(evaluation);
        evaluation.publish(now);
        evaluationRepository.saveAndFlush(evaluation);
        auditService.record(userId, "FINANCIAL_SCORE_CALCULATED", "WELLNESS_EVALUATION", evaluation.getId());
        return ScoreResponse.from(evaluation);
    }

    @Transactional(readOnly = true)
    public ScorePageResponse list(UUID userId, String currency, int page, int size) {
        requireCurrency(currency);
        Page<WellnessEvaluation> scores = evaluationRepository
                .findByUser_IdAndCurrencyAndMethodVersionOrderByCreatedAtDescIdDesc(
                        userId, currency, FinancialWellnessScoreCalculator.VERSION,
                        PageRequest.of(page, size));
        return new ScorePageResponse(scores.getContent().stream().map(ScoreResponse::from).toList(),
                page, size, scores.getTotalElements(), scores.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ScoreResponse get(UUID userId, UUID scoreId) {
        return evaluationRepository.findByIdAndUser_IdAndMethodVersion(
                        scoreId, userId, FinancialWellnessScoreCalculator.VERSION)
                .map(ScoreResponse::from)
                .orElseThrow(FinancialScoreException::notFound);
    }

    private Map<YearMonth, MonthlyAmounts> monthlyAmounts(
            LocalDate historyStart, List<EligibleTransactionRow> transactions) {
        Map<YearMonth, MonthlyAmounts> monthly = new java.util.LinkedHashMap<>();
        YearMonth first = YearMonth.from(historyStart);
        for (int offset = 0; offset < 6; offset++) {
            monthly.put(first.plusMonths(offset), new MonthlyAmounts());
        }
        transactions.forEach(transaction -> {
            MonthlyAmounts amounts = monthly.get(YearMonth.from(transaction.getBookingDate()));
            if (amounts == null) return;
            if ("CREDIT".equals(transaction.getDirection())) {
                amounts.income = amounts.income.add(transaction.getAmount()).setScale(4);
            } else if ("DEBIT".equals(transaction.getDirection())) {
                amounts.expense = amounts.expense.add(transaction.getAmount()).setScale(4);
            }
        });
        return monthly;
    }

    private List<BigDecimal> salaryRatios(
            List<CompletedPlanRow> plans, Map<YearMonth, MonthlyAmounts> monthly) {
        List<BigDecimal> ratios = new ArrayList<>();
        for (CompletedPlanRow plan : plans) {
            BigDecimal budget = plan.getPlannedExpenseBudget().setScale(4);
            if (budget.signum() <= 0) continue;
            BigDecimal actual = monthly.getOrDefault(
                    YearMonth.from(plan.getPeriodMonth()), new MonthlyAmounts()).expense;
            ratios.add(actual.divide(budget, 4, RoundingMode.HALF_EVEN));
        }
        return List.copyOf(ratios);
    }

    private GoalInputs goalInputs(
            UUID userId, List<FinancialGoal> goals, ZoneId zone, LocalDate asOfDate) {
        List<BigDecimal> scores = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (FinancialGoal goal : goals) {
            List<GoalContribution> contributions = contributionRepository
                    .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateAscCreatedAtAsc(
                            goal.getId(), userId);
            canonical.add(goal.getId() + "|" + goal.getStatus() + "|" + goal.getTargetAmount()
                    + "|" + goal.getInitialAmount() + "|" + goal.getTargetDate() + "|"
                    + contributions.stream().map(contribution -> contribution.getId() + ":"
                            + contribution.getAmount() + ":" + contribution.getContributionDate())
                            .reduce((left, right) -> left + "," + right).orElse(""));
            if (goal.getStatus() == FinancialGoal.Status.ACHIEVED) {
                scores.add(ONE_HUNDRED);
                continue;
            }
            if (goal.getTargetDate() == null) continue;
            GoalProgress progress = goalCalculator.calculateProgress(
                    goal.getTargetAmount(), goal.getInitialAmount(),
                    contributions.stream().map(GoalContribution::getAmount).toList(),
                    ZERO, goal.getTargetDate(), asOfDate);
            LocalDate startDate = LocalDate.ofInstant(goal.getCreatedAt(), zone);
            scores.add(onScheduleScore(
                    progress.progressPercentage(), startDate, goal.getTargetDate(), asOfDate));
        }
        return new GoalInputs(List.copyOf(scores), List.copyOf(canonical));
    }

    private BigDecimal onScheduleScore(
            BigDecimal actualProgress, LocalDate start, LocalDate target, LocalDate asOfDate) {
        long totalDays = ChronoUnit.DAYS.between(start, target);
        if (totalDays <= 0) {
            return actualProgress.min(ONE_HUNDRED).setScale(4);
        }
        long elapsedDays = Math.max(0, Math.min(totalDays, ChronoUnit.DAYS.between(start, asOfDate)));
        if (elapsedDays == 0) return ONE_HUNDRED;
        BigDecimal expectedProgress = BigDecimal.valueOf(elapsedDays)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalDays), 12, RoundingMode.HALF_EVEN);
        return actualProgress.multiply(ONE_HUNDRED)
                .divide(expectedProgress, 4, RoundingMode.HALF_EVEN)
                .min(ONE_HUNDRED).max(BigDecimal.ZERO).setScale(4);
    }

    private String fingerprint(
            String currency, ZoneId zone, LocalDate asOfDate, LocalDate historyStart,
            LocalDate historyEnd, List<EligibleTransactionRow> transactions,
            List<CompletedPlanRow> plans, List<String> goals) {
        StringBuilder canonical = new StringBuilder(FinancialWellnessScoreCalculator.VERSION)
                .append('|').append(currency).append('|').append(zone.getId())
                .append('|').append(asOfDate).append('|').append(historyStart)
                .append('|').append(historyEnd);
        transactions.forEach(transaction -> canonical.append("|T:")
                .append(transaction.getTransactionId()).append(':')
                .append(transaction.getBookingDate()).append(':')
                .append(transaction.getAmount().setScale(4).toPlainString()).append(':')
                .append(transaction.getDirection()).append(':').append(transaction.getSourceType())
                .append(':').append(transaction.getProviderStatus()).append(':')
                .append(transaction.getCategoryCode()));
        plans.forEach(plan -> canonical.append("|P:").append(plan.getPlanId()).append(':')
                .append(plan.getPeriodMonth()).append(':')
                .append(plan.getPlannedExpenseBudget().setScale(4).toPlainString()));
        goals.forEach(goal -> canonical.append("|G:").append(goal));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ZoneId userTimeZone(UUID userId) {
        String value = preferenceRepository.findById(userId)
                .map(preference -> preference.getTimeZone()).orElse(FALLBACK_TIME_ZONE);
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw FinancialScoreException.invalid("Stored timezone must be a valid IANA identifier");
        }
    }

    private void requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw FinancialScoreException.invalid("Currency must be an uppercase ISO 4217 code");
        }
    }

    private static final class MonthlyAmounts {
        private BigDecimal income = ZERO;
        private BigDecimal expense = ZERO;
    }

    private record GoalInputs(List<BigDecimal> scores, List<String> canonicalInputs) {
    }
}
