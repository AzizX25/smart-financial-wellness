package com.azizx25.smartfinancialwellness.assistance;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AssistanceClientTests {
    @Test
    void receiptContractKeepsOnlyImageInputAndNormalizedReviewFields() {
        var builder = RestClient.builder().baseUrl("http://local-ai.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AssistanceClient(builder.build(), properties(true));
        server.expect(requestTo("http://local-ai.test/api/v1/receipts/extract"))
                .andExpect(content().json("""
                        {"contractVersion":"local-financial-assistance-v1","imageBase64":"AQID"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"contractVersion":"local-financial-assistance-v1","status":"EXTRACTED",
                         "merchant":"Lefties","date":"2026-09-11","amount":"309.700","currency":"TND"}
                        """, MediaType.APPLICATION_JSON));
        var result = client.receipt(new byte[]{1, 2, 3});
        assertThat(result.status()).isEqualTo("EXTRACTED");
        assertThat(result.date()).isEqualTo("2026-09-11");
        assertThat(result.amount()).isEqualTo("309.700");
        assertThat(result.currency()).isEqualTo("TND");
        server.verify();
    }

    @Test
    void sendsOnlyRuleAndFactCodesAndRejectsInventedNumbersAndForeignReferences() {
        var builder = RestClient.builder().baseUrl("http://local-ai.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AssistanceClient(builder.build(), properties(true));
        for (String text : new String[]{"Comparez les dépenses et votre budget.", "Épargnez 500 TND"}) {
            server.expect(requestTo("http://local-ai.test/api/v1/recommendations/explain"))
                    .andExpect(content().json("""
                            {"contractVersion":"local-financial-assistance-v1","ruleCode":"SAVINGS_MARGIN","factCode":"SAVINGS_RATE"}
                            """, org.springframework.test.json.JsonCompareMode.STRICT))
                    .andRespond(withSuccess("""
                            {"contractVersion":"local-financial-assistance-v1","status":"EXPLAINED","text":"%s","factCodes":["SAVINGS_RATE"]}
                            """.formatted(text), MediaType.APPLICATION_JSON));
        }
        assertThat(client.explain("SAVINGS_MARGIN", "SAVINGS_RATE").status()).isEqualTo("EXPLAINED");
        assertThat(client.explain("SAVINGS_MARGIN", "SAVINGS_RATE").status()).isEqualTo("INVALID_RESPONSE");
        server.verify();
    }

    @Test
    void defaultsDoNotContactAiAndTimeoutsStayBounded() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AssistanceClient(builder.build(), properties(false));
        assertThat(client.explain("SAVINGS_MARGIN", "SAVINGS_RATE").status()).isEqualTo("UNAVAILABLE");
        assertThat(client.receipt(new byte[]{1}).status()).isEqualTo("UNAVAILABLE");
        server.verify();
        assertThatThrownBy(() -> new AssistanceProperties(URI.create("http://ai"),
                Duration.ofSeconds(2), Duration.ofSeconds(61), true, true)).isInstanceOf(IllegalArgumentException.class);
    }

    private AssistanceProperties properties(boolean enabled) {
        return new AssistanceProperties(URI.create("http://local-ai.test"),
                Duration.ofSeconds(2), Duration.ofSeconds(35), enabled, enabled);
    }
}
