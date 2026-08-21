package com.azizx25.smartfinancialwellness.category.infrastructure;

import java.math.BigDecimal;
import java.util.List;

public interface MerchantRecognitionAiClient {

    List<Prediction> predict(List<RequestItem> items);

    record RequestItem(
            String requestKey, String sanitizedMerchantText, String direction,
            String currency, String featureVersion) {
    }

    record Prediction(
            String requestKey, String status, String canonicalMerchant,
            String categoryCode, String matchMethod, BigDecimal matchScore,
            String modelId, String modelVersion, String featureVersion,
            boolean experimental, boolean syntheticTrainingData, boolean guaranteed,
            String fallbackReason, String rationale, List<String> evidenceUrls,
            String providerId, String catalogVersion, String taxonomyVersion) {
        public Prediction {
            evidenceUrls = evidenceUrls == null ? List.of() : List.copyOf(evidenceUrls);
        }
    }
}
