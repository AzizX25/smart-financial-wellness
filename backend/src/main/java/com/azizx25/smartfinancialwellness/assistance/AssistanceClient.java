package com.azizx25.smartfinancialwellness.assistance;

import java.util.Base64;
import java.util.List;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class AssistanceClient {
    public static final String VERSION = "local-financial-assistance-v1";
    private final RestClient client;
    private final AssistanceProperties properties;

    public AssistanceClient(RestClient client, AssistanceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public ExplanationResult explain(String rule, String fact) {
        if (!properties.explainerEnabled()) return new ExplanationResult(VERSION, "UNAVAILABLE", null, List.of());
        try {
            var response = client.post().uri("/api/v1/recommendations/explain")
                    .body(new ExplanationRequest(VERSION, rule, fact)).retrieve().body(ExplanationResult.class);
            if (response == null || !VERSION.equals(response.contractVersion())) throw new IllegalArgumentException();
            if (response.status() == null || !List.of("EXPLAINED", "UNAVAILABLE", "INVALID_RESPONSE").contains(response.status())) throw new IllegalArgumentException();
            if ("EXPLAINED".equals(response.status()) && (response.text() == null
                    || response.text().isBlank() || response.text().length() > 600
                    || response.text().matches("(?s).*\\p{N}.*")
                    || response.text().matches("(?is).*?(https?://|<|>|garanti|rendement|investis|crypto|acheter.*actions).*?")
                    || !List.of(fact).equals(response.factCodes()))) throw new IllegalArgumentException();
            if (!"EXPLAINED".equals(response.status()) && (response.text() != null
                    || response.factCodes() == null || !response.factCodes().isEmpty())) throw new IllegalArgumentException();
            return response;
        } catch (RestClientException exception) {
            return new ExplanationResult(VERSION, "UNAVAILABLE", null, List.of());
        } catch (IllegalArgumentException exception) {
            return new ExplanationResult(VERSION, "INVALID_RESPONSE", null, List.of());
        }
    }

    public ReceiptResult receipt(byte[] bytes) {
        if (!properties.visionEnabled()) return new ReceiptResult(VERSION, "UNAVAILABLE", null, null, null, null);
        try {
            var response = client.post().uri("/api/v1/receipts/extract")
                    .body(new ReceiptRequest(VERSION, Base64.getEncoder().encodeToString(bytes)))
                    .retrieve().body(ReceiptResult.class);
            if (response == null || response.status() == null || !VERSION.equals(response.contractVersion())
                    || !List.of("EXTRACTED", "PARTIAL", "UNAVAILABLE", "INVALID_RESPONSE").contains(response.status())) {
                throw new IllegalArgumentException();
            }
            if (List.of("UNAVAILABLE", "INVALID_RESPONSE").contains(response.status())
                    && (response.merchant() != null || response.date() != null || response.amount() != null
                    || response.currency() != null)) throw new IllegalArgumentException();
            return response;
        } catch (RestClientException exception) {
            return new ReceiptResult(VERSION, "UNAVAILABLE", null, null, null, null);
        } catch (IllegalArgumentException exception) {
            return new ReceiptResult(VERSION, "INVALID_RESPONSE", null, null, null, null);
        }
    }

    public record ExplanationRequest(String contractVersion, String ruleCode, String factCode) { }
    public record ExplanationResult(String contractVersion, String status, String text, List<String> factCodes) { }
    public record ReceiptRequest(String contractVersion, String imageBase64) { }
    public record ReceiptResult(String contractVersion, String status, String merchant,
            String date, String amount, String currency) { }
}
