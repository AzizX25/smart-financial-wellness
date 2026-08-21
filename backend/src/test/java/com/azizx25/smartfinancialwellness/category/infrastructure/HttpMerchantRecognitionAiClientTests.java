package com.azizx25.smartfinancialwellness.category.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpMerchantRecognitionAiClientTests {

    private static final String RESPONSE = """
            {"items":[{
              "requestKey":"merchant-1",
              "status":"RECOGNIZED",
              "canonicalMerchant":"Carrefour",
              "categoryCode":"FOOD",
              "matchMethod":"EXACT_ALIAS",
              "matchScore":1.0000,
              "modelId":"merchant-recognition-synthetic-v1",
              "modelVersion":"1.0.0",
              "featureVersion":"merchant-recognition-features-v1",
              "experimental":true,
              "syntheticTrainingData":true,
              "guaranteed":false,
              "candidateCategoryCodes":[]
            }]}
            """;

    @Test
    void bindsIndependentMerchantAndForecastTimeoutDefaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MerchantAiClientConfiguration.class)
                .run(context -> {
                    MerchantAiClientProperties merchant = context.getBean(MerchantAiClientProperties.class);

                    assertThat(merchant.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(merchant.readTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(context.getEnvironment().getProperty(
                            "app.integrations.ai-forecast.connect-timeout")).isEqualTo("2s");
                    assertThat(context.getEnvironment().getProperty(
                            "app.integrations.ai-forecast.read-timeout")).isEqualTo("5s");
                });
    }

    @Test
    void serializesTheCanonicalStrictMerchantRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://merchant-ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://merchant-ai.test/api/v1/merchant-recognition/predict"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "items": [{
                            "requestKey": "merchant-1",
                            "sanitizedMerchantText": "carrefour la marsa",
                            "direction": "DEBIT",
                            "currency": "TND",
                            "featureVersion": "merchant-recognition-features-v1"
                          }]
                        }
                        """, true))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        MerchantRecognitionAiClient client = new HttpMerchantRecognitionAiClient(builder.build());

        List<MerchantRecognitionAiClient.Prediction> predictions = client.predict(List.of(
                new MerchantRecognitionAiClient.RequestItem(
                        "merchant-1", "carrefour la marsa", "DEBIT", "TND",
                        "merchant-recognition-features-v1")));

        assertThat(predictions).singleElement().satisfies(prediction -> {
            assertThat(prediction.requestKey()).isEqualTo("merchant-1");
            assertThat(prediction.status()).isEqualTo("RECOGNIZED");
            assertThat(prediction.candidateCategoryCodes()).isEmpty();
        });
        server.verify();
    }

    @Test
    void configuredClientDoesNotAttemptAnH2cUpgrade() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/merchant-recognition/predict", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            MerchantAiClientProperties properties = new MerchantAiClientProperties(
                    baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2),
                    "merchant-recognition-synthetic-v1", "1.0.0",
                    "merchant-recognition-features-v1", "ollama", "qwen3:4b",
                    "ollama-api-v1", "merchant-enrichment-features-v1",
                    "merchant-catalog-v1", "system-taxonomy-v2");
            MerchantRecognitionAiClient client = new MerchantAiClientConfiguration()
                    .merchantRecognitionAiClient(properties);

            assertThat(client.predict(List.of(new MerchantRecognitionAiClient.RequestItem(
                    "merchant-1", "carrefour la marsa", "DEBIT", "TND",
                    "merchant-recognition-features-v1")))).hasSize(1);
            assertThat(upgradeHeader.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
