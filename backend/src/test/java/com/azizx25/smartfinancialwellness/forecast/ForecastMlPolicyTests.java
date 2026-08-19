package com.azizx25.smartfinancialwellness.forecast;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.FallbackReason;
import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient.ForecastAiPrediction;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClientProperties;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastResult.MlStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastMlPolicyTests {

    @Test
    void day25RemainsEligibleAndCallsTheFrozenModel() {
        AtomicInteger calls = new AtomicInteger();
        ForecastAiClient client = (currency, features) -> {
            calls.incrementAndGet();
            return new ForecastAiPrediction(
                    "forecast-huber-synthetic-tnd", "1.0.0",
                    "forecast-residual-features-v1", "TND", "10.0000",
                    true, true, false);
        };
        ForecastAiClientProperties properties = new ForecastAiClientProperties(
                URI.create("http://localhost:8000"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), "forecast-huber-synthetic-tnd", "1.0.0",
                "forecast-residual-features-v1");
        ForecastService service = new ForecastService(
                null, null, null, client, properties, null, null, null, null);
        BigDecimal zero = new BigDecimal("0.0000");
        ForecastFeatures day25 = new ForecastFeatures(
                "forecast-residual-features-v1", "2026-08", 25, 6,
                zero, zero, zero, 0, zero, zero, zero, zero,
                zero, zero, zero, 6, "NONE");

        ForecastService.MlOutcome outcome = service.resolveMl(
                "TND", day25, FallbackReason.NONE);

        assertThat(outcome.status()).isEqualTo(MlStatus.APPLIED);
        assertThat(outcome.residual()).isEqualByComparingTo("10.0000");
        assertThat(calls).hasValue(1);
    }
}
