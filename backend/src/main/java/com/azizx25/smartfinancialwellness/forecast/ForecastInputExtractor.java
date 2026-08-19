package com.azizx25.smartfinancialwellness.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.azizx25.smartfinancialwellness.bank.persistence.BalanceSnapshot;
import com.azizx25.smartfinancialwellness.bank.persistence.BalanceSnapshotRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastFinancialDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastInputExtractor {

    private static final int MONEY_SCALE = 4;
    private static final int HISTORY_MONTHS = 6;
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private final ForecastFinancialDataRepository financialDataRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;

    public ForecastInputExtractor(
            ForecastFinancialDataRepository financialDataRepository,
            BalanceSnapshotRepository balanceSnapshotRepository) {
        this.financialDataRepository = financialDataRepository;
        this.balanceSnapshotRepository = balanceSnapshotRepository;
    }

    @Transactional(readOnly = true)
    public ForecastInput extract(
            UUID userId,
            String currency,
            ZoneId userTimeZone,
            LocalDate calculationDate) {
        Objects.requireNonNull(userId, "userId is required");
        requireCurrency(currency);
        Objects.requireNonNull(userTimeZone, "userTimeZone is required");
        Objects.requireNonNull(calculationDate, "calculationDate is required");

        List<FinancialAccount> accounts = financialDataRepository.findEligibleAccounts(
                userId, currency, FinancialAccount.Status.ACTIVE);
        if (accounts.isEmpty()) {
            return ForecastInput.unavailable(
                    UnavailableReason.NO_ELIGIBLE_ACCOUNTS, userTimeZone, 0, null);
        }

        List<UUID> accountIds = accounts.stream().map(FinancialAccount::getId).toList();
        Instant futureBoundary = calculationDate.plusDays(1)
                .atStartOfDay(userTimeZone)
                .toInstant();
        List<BalanceSnapshot> snapshots = balanceSnapshotRepository.findUsableSnapshots(
                accountIds, futureBoundary);
        Map<UUID, Map<LocalDate, BalanceSnapshot>> latestByAccountAndDate =
                indexLatestSnapshots(accountIds, snapshots, userTimeZone);

        if (latestByAccountAndDate.values().stream().anyMatch(Map::isEmpty)) {
            return ForecastInput.unavailable(
                    UnavailableReason.ACCOUNT_MISSING_SNAPSHOT,
                    userTimeZone,
                    accounts.size(),
                    null);
        }

        Set<LocalDate> commonDates = new HashSet<>(
                latestByAccountAndDate.get(accountIds.getFirst()).keySet());
        accountIds.stream().skip(1)
                .map(latestByAccountAndDate::get)
                .map(Map::keySet)
                .forEach(commonDates::retainAll);
        LocalDate commonCutoff = commonDates.stream().max(LocalDate::compareTo).orElse(null);
        if (commonCutoff == null) {
            return ForecastInput.unavailable(
                    UnavailableReason.NO_COMMON_CUTOFF,
                    userTimeZone,
                    accounts.size(),
                    null);
        }

        if (!YearMonth.from(commonCutoff).equals(YearMonth.from(calculationDate))) {
            return ForecastInput.unavailable(
                    UnavailableReason.CUTOFF_OUTSIDE_CURRENT_MONTH,
                    userTimeZone,
                    accounts.size(),
                    commonCutoff);
        }

        List<BalanceSnapshot> selectedSnapshots = accountIds.stream()
                .map(accountId -> latestByAccountAndDate.get(accountId).get(commonCutoff))
                .toList();
        BigDecimal startingBalance = selectedSnapshots.stream()
                .map(BalanceSnapshot::getLedgerBalance)
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE), BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

        YearMonth targetMonth = YearMonth.from(calculationDate);
        LocalDate historyStart = targetMonth.minusMonths(HISTORY_MONTHS).atDay(1);
        LocalDate historyEndExclusive = targetMonth.atDay(1);
        List<HistoricalCashFlow> historicalCashFlows = financialDataRepository
                .findEligibleCashFlows(
                        userId, accountIds, currency, historyStart, historyEndExclusive)
                .stream()
                .map(row -> new HistoricalCashFlow(
                        row.getBookingDate(),
                        row.getAmount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
                        CashFlowDirection.valueOf(row.getDirection())))
                .toList();
        List<HistoricalCashFlow> currentMonthCashFlows = financialDataRepository
                .findEligibleCashFlows(
                        userId, accountIds, currency, targetMonth.atDay(1), commonCutoff.plusDays(1))
                .stream()
                .map(row -> new HistoricalCashFlow(
                        row.getBookingDate(),
                        row.getAmount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
                        CashFlowDirection.valueOf(row.getDirection())))
                .toList();

        return ForecastInput.available(
                startingBalance,
                commonCutoff,
                selectedSnapshots.stream()
                        .map(BalanceSnapshot::getObservedAt)
                        .min(Instant::compareTo)
                        .orElseThrow(),
                userTimeZone,
                accounts.size(),
                historicalCashFlows,
                currentMonthCashFlows);
    }

    private Map<UUID, Map<LocalDate, BalanceSnapshot>> indexLatestSnapshots(
            List<UUID> accountIds,
            List<BalanceSnapshot> snapshots,
            ZoneId userTimeZone) {
        Map<UUID, Map<LocalDate, BalanceSnapshot>> indexed = new LinkedHashMap<>();
        accountIds.forEach(accountId -> indexed.put(accountId, new HashMap<>()));
        snapshots.forEach(snapshot -> {
            LocalDate localDate = snapshot.getObservedAt().atZone(userTimeZone).toLocalDate();
            indexed.get(snapshot.getFinancialAccountId()).putIfAbsent(localDate, snapshot);
        });
        return indexed;
    }

    private void requireCurrency(String currency) {
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
        }
    }

    public enum UnavailableReason {
        NONE,
        NO_ELIGIBLE_ACCOUNTS,
        ACCOUNT_MISSING_SNAPSHOT,
        NO_COMMON_CUTOFF,
        CUTOFF_OUTSIDE_CURRENT_MONTH
    }

    public enum CashFlowDirection {
        CREDIT, DEBIT
    }

    public record HistoricalCashFlow(
            LocalDate bookingDate,
            BigDecimal amount,
            CashFlowDirection direction) {

        public HistoricalCashFlow {
            Objects.requireNonNull(bookingDate, "bookingDate is required");
            Objects.requireNonNull(amount, "amount is required");
            Objects.requireNonNull(direction, "direction is required");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
            amount = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        }
    }

    public record ForecastInput(
            UnavailableReason unavailableReason,
            BigDecimal startingBalance,
            LocalDate cutoffDate,
            Instant snapshotCutoff,
            ZoneId timeZone,
            int selectedAccountCount,
            List<HistoricalCashFlow> historicalCashFlows,
            List<HistoricalCashFlow> currentMonthCashFlows) {

        public ForecastInput {
            Objects.requireNonNull(unavailableReason, "unavailableReason is required");
            Objects.requireNonNull(timeZone, "timeZone is required");
            historicalCashFlows = List.copyOf(historicalCashFlows);
            currentMonthCashFlows = List.copyOf(currentMonthCashFlows);
        }

        static ForecastInput available(
                BigDecimal startingBalance,
                LocalDate cutoffDate,
                Instant snapshotCutoff,
                ZoneId timeZone,
                int selectedAccountCount,
                List<HistoricalCashFlow> historicalCashFlows,
                List<HistoricalCashFlow> currentMonthCashFlows) {
            return new ForecastInput(
                    UnavailableReason.NONE,
                    startingBalance,
                    cutoffDate,
                    snapshotCutoff,
                    timeZone,
                    selectedAccountCount,
                    historicalCashFlows,
                    currentMonthCashFlows);
        }

        static ForecastInput unavailable(
                UnavailableReason reason,
                ZoneId timeZone,
                int selectedAccountCount,
                LocalDate cutoffDate) {
            return new ForecastInput(
                    reason,
                    null,
                    cutoffDate,
                    null,
                    timeZone,
                    selectedAccountCount,
                    List.of(),
                    List.of());
        }

        public boolean available() {
            return unavailableReason == UnavailableReason.NONE;
        }
    }
}
