package com.azizx25.smartfinancialwellness.forecast;

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
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.CalculationRequest;
import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.ForecastCalculation;
import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.FallbackReason;
import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.ForecastInput;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastPageResponse;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastResponse;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastSimulationRequest;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastSimulationResponse;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient.ForecastAiPrediction;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClientProperties;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiExceptions.InvalidResponse;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiExceptions.Unavailable;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastResult;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastResult.MlStatus;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastResultRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastService {

    private static final int SCALE = 4;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final ForecastInputExtractor extractor;
    private final DeterministicForecastCalculator calculator;
    private final ForecastFeatureAssembler featureAssembler;
    private final ForecastAiClient aiClient;
    private final ForecastAiClientProperties aiProperties;
    private final ForecastResultRepository repository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ForecastService(
            ForecastInputExtractor extractor, DeterministicForecastCalculator calculator,
            ForecastFeatureAssembler featureAssembler, ForecastAiClient aiClient,
            ForecastAiClientProperties aiProperties, ForecastResultRepository repository,
            AppUserRepository userRepository, AuditService auditService, Clock clock) {
        this.extractor = extractor;
        this.calculator = calculator;
        this.featureAssembler = featureAssembler;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ForecastResponse generateCurrent(UUID userId, String currency, String timeZoneId) {
        requireCurrency(currency);
        ZoneId zone = requireZone(timeZoneId);
        LocalDate calculationDate = LocalDate.now(clock.withZone(zone));
        ForecastInput input = extractor.extract(userId, currency, zone, calculationDate);
        if (!input.available()) {
            return unavailable(currency, zone, input.unavailableReason().name());
        }
        ForecastCalculation calculation = calculator.calculate(new CalculationRequest(
                input.startingBalance(), calculationDate, input.cutoffDate(), zone,
                input.selectedAccountCount(), input.historicalCashFlows()));
        ForecastFeatures features = featureAssembler.assemble(input, calculation);
        MlOutcome ml = resolveMl(currency, features, calculation.metadata().fallbackReason());
        BigDecimal deterministicEnd = money(calculation.predictedEndBalance());
        BigDecimal finalEnd = ml.residual() == null
                ? deterministicEnd
                : deterministicEnd.add(ml.residual()).setScale(SCALE);
        String fingerprint = fingerprint(input, calculation, features, ml);
        return repository.findByUser_IdAndCurrencyAndCanonicalInputFingerprintAndMlStatus(
                        userId, currency, fingerprint, ml.status())
                .map(this::response)
                .orElseGet(() -> persist(userId, currency, zone, input, calculation,
                        ml, deterministicEnd, finalEnd, fingerprint));
    }

    @Transactional(readOnly = true)
    public ForecastPageResponse list(UUID userId, String currency, int page, int size) {
        requireCurrency(currency);
        Page<ForecastResult> results = repository
                .findByUser_IdAndCurrencyOrderByCreatedAtDescIdDesc(
                        userId, currency, PageRequest.of(page, size));
        return new ForecastPageResponse(results.getContent().stream().map(this::response).toList(),
                page, size, results.getTotalElements(), results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ForecastResponse get(UUID userId, UUID forecastId) {
        return response(owned(userId, forecastId));
    }

    @Transactional(readOnly = true)
    public ForecastSimulationResponse simulate(
            UUID userId, UUID forecastId, ForecastSimulationRequest request) {
        ForecastResult result = owned(userId, forecastId);
        BigDecimal income = optionalMoney(request.additionalIncome());
        BigDecimal expense = optionalMoney(request.additionalExpense());
        if (income.signum() < 0 || expense.signum() < 0) {
            throw ForecastException.invalid("Simulation amounts must be non-negative");
        }
        BigDecimal simulated = result.getFinalPredictedEndBalance()
                .add(income).subtract(expense).setScale(SCALE);
        return new ForecastSimulationResponse(
                result.getId(), result.getCurrency(), result.getFinalPredictedEndBalance(),
                income, expense, simulated, result.getMlStatus().name(), result.getModelId(),
                result.getModelVersion(), result.isExperimental(),
                result.isSyntheticTrainingData(), false);
    }

    private ForecastResponse persist(
            UUID userId, String currency, ZoneId zone, ForecastInput input,
            ForecastCalculation calculation, MlOutcome ml, BigDecimal deterministicEnd,
            BigDecimal finalEnd, String fingerprint) {
        ForecastResult result = new ForecastResult(
                userRepository.getReferenceById(userId), currency, zone.getId(),
                YearMonth.from(input.cutoffDate()).atDay(1), input.cutoffDate(),
                input.snapshotCutoff(), input.startingBalance(),
                calculation.expectedRemainingIncome(), calculation.expectedRemainingExpense(),
                calculation.expectedRemainingNetFlow(), deterministicEnd, ml.status(),
                ml.residual(), finalEnd, calculation.metadata().method(),
                calculation.metadata().methodVersion(), ml.modelId(), ml.modelVersion(),
                ml.featureVersion(), ml.applied(), ml.applied(), false, fingerprint, clock.instant());
        repository.saveAndFlush(result);
        auditService.record(userId, "FORECAST_GENERATED", "FORECAST_RESULT", result.getId());
        return response(result);
    }

    MlOutcome resolveMl(String currency, ForecastFeatures features, FallbackReason fallback) {
        if (!"TND".equals(currency)) {
            return MlOutcome.fallback(MlStatus.UNSUPPORTED_CURRENCY);
        }
        if (features.usableHistoryCount() != 6 || fallback != FallbackReason.NONE) {
            return MlOutcome.fallback(MlStatus.INSUFFICIENT_HISTORY);
        }
        try {
            ForecastAiPrediction prediction = aiClient.predict(currency, features);
            if (!aiProperties.expectedModelId().equals(prediction.modelId())
                    || !aiProperties.expectedModelVersion().equals(prediction.modelVersion())
                    || !aiProperties.expectedFeatureVersion().equals(prediction.featureVersion())) {
                return MlOutcome.fallback(MlStatus.MODEL_VERSION_MISMATCH);
            }
            if (!currency.equals(prediction.currency()) || !prediction.experimental()
                    || !prediction.syntheticTrainingData() || prediction.guaranteed()) {
                return MlOutcome.fallback(MlStatus.INVALID_RESPONSE);
            }
            BigDecimal residual = money(new BigDecimal(prediction.predictedResidual()));
            return new MlOutcome(MlStatus.APPLIED, residual, prediction.modelId(),
                    prediction.modelVersion(), prediction.featureVersion());
        } catch (Unavailable exception) {
            return MlOutcome.fallback(MlStatus.SERVICE_UNAVAILABLE);
        } catch (InvalidResponse | NumberFormatException | ArithmeticException exception) {
            return MlOutcome.fallback(MlStatus.INVALID_RESPONSE);
        }
    }

    private String fingerprint(
            ForecastInput input, ForecastCalculation calculation,
            ForecastFeatures features, MlOutcome ml) {
        String canonical = input.timeZone().getId() + '|' + input.cutoffDate() + '|'
                + input.snapshotCutoff() + '|' + money(input.startingBalance()).toPlainString() + '|'
                + features.toString() + '|' + calculation.metadata().method() + '|'
                + calculation.metadata().methodVersion() + '|' + ml.status() + '|'
                + (ml.residual() == null ? "" : ml.residual().toPlainString()) + '|'
                + (ml.modelId() == null ? "" : ml.modelId()) + '|'
                + (ml.modelVersion() == null ? "" : ml.modelVersion());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ForecastResult owned(UUID userId, UUID id) {
        return repository.findByIdAndUser_Id(id, userId).orElseThrow(ForecastException::notFound);
    }

    private ForecastResponse response(ForecastResult result) {
        return new ForecastResponse(true, null, result.getId(), result.getCurrency(),
                result.getTimeZone(), result.getForecastMonth().toString(),
                result.getLocalCutoffDate(), result.getSnapshotCutoff(), result.getStartingBalance(),
                result.getDeterministicRemainingIncome(), result.getDeterministicRemainingExpense(),
                result.getDeterministicRemainingNetFlow(), result.getDeterministicPredictedEndBalance(),
                result.getMlStatus().name(), result.getMlPredictedResidual(),
                result.getFinalPredictedEndBalance(), result.getDeterministicMethod(),
                result.getDeterministicMethodVersion(), result.getModelId(), result.getModelVersion(),
                result.getFeatureContractVersion(), result.isExperimental(),
                result.isSyntheticTrainingData(), result.isGuaranteed(), result.getCreatedAt());
    }

    private ForecastResponse unavailable(String currency, ZoneId zone, String reason) {
        return new ForecastResponse(
                false, reason, null, currency, zone.getId(),
                null, null, null,
                null, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                false, false, false, null);
    }

    private BigDecimal optionalMoney(BigDecimal value) {
        return value == null ? ZERO : money(value);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private void requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw ForecastException.invalid("Currency must be an uppercase ISO 4217 code");
        }
    }

    private ZoneId requireZone(String timeZoneId) {
        try {
            return ZoneId.of(timeZoneId);
        } catch (DateTimeException | NullPointerException exception) {
            throw ForecastException.invalid("Timezone must be a valid IANA identifier");
        }
    }

    record MlOutcome(
            MlStatus status, BigDecimal residual, String modelId,
            String modelVersion, String featureVersion) {
        static MlOutcome fallback(MlStatus status) {
            return new MlOutcome(status, null, null, null, null);
        }

        boolean applied() { return status == MlStatus.APPLIED; }
    }
}
