package com.azizx25.smartfinancialwellness.forecast.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class ForecastAiClientConfigurationTests {

    @Test
    void configuredClientDoesNotAttemptAnH2cUpgrade() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/forecast/predict-residual", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {"modelId":"forecast-huber-synthetic-tnd","modelVersion":"1.0.0",
                     "featureVersion":"forecast-residual-features-v1","currency":"TND",
                     "predictedResidual":"10.0000","experimental":true,
                     "syntheticTrainingData":true,"guaranteed":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ForecastAiClientProperties properties = new ForecastAiClientProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(2), Duration.ofSeconds(5),
                    "forecast-huber-synthetic-tnd", "1.0.0", "forecast-residual-features-v1");
            ForecastAiClient client = new ForecastAiClientConfiguration().forecastAiClient(properties);
            BigDecimal zero = new BigDecimal("0.0000");
            ForecastFeatures features = new ForecastFeatures(
                    "forecast-residual-features-v1", "2026-08", 18, 13,
                    zero, zero, zero, 0, zero, zero, zero, zero,
                    zero, zero, zero, 6, "NONE");

            assertThat(client.predict("TND", features).predictedResidual()).isEqualTo("10.0000");
            assertThat(upgradeHeader.get()).isNull();
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        } finally {
            server.stop(0);
        }
    }
}
