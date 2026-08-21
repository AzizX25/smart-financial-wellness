package com.azizx25.smartfinancialwellness.category;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.MerchantRecognitionPersistence.Candidate;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantRecognitionRunResponse;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiClientProperties;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiExceptions.InvalidResponse;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiExceptions.Unavailable;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient.Prediction;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient.RequestItem;
import org.springframework.stereotype.Service;

@Service
public class MerchantRecognitionService {

    private static final int BATCH_LIMIT = 50;
    private static final Set<String> METHODS = Set.of("EXACT_ALIAS", "FUZZY_ALIAS", "ML");

    private final DeterministicCategorizationService deterministicService;
    private final MerchantRecognitionPersistence persistence;
    private final MerchantPrivacySanitizer sanitizer;
    private final MerchantRecognitionAiClient aiClient;
    private final MerchantAiClientProperties properties;

    public MerchantRecognitionService(
            DeterministicCategorizationService deterministicService,
            MerchantRecognitionPersistence persistence,
            MerchantPrivacySanitizer sanitizer,
            MerchantRecognitionAiClient aiClient,
            MerchantAiClientProperties properties) {
        this.deterministicService = deterministicService;
        this.persistence = persistence;
        this.sanitizer = sanitizer;
        this.aiClient = aiClient;
        this.properties = properties;
    }

    public MerchantRecognitionRunResponse generate(UUID userId) {
        deterministicService.categorizeUncategorized(userId);
        List<Candidate> candidates = persistence.loadCandidates(userId, BATCH_LIMIT);
        int unsupported = (int) candidates.stream().filter(candidate -> !"TND".equals(candidate.currency())).count();
        Map<String, Candidate> requested = new LinkedHashMap<>();
        List<RequestItem> items = candidates.stream()
                .filter(candidate -> "TND".equals(candidate.currency()))
                .map(candidate -> request(candidate, requested))
                .filter(item -> !item.sanitizedMerchantText().isBlank())
                .toList();
        if (items.isEmpty()) {
            return new MerchantRecognitionRunResponse(
                    0, 0, 0, unsupported,
                    unsupported > 0 ? "UNSUPPORTED_CURRENCY" : "UNRECOGNIZED");
        }

        List<Prediction> predictions;
        try {
            predictions = aiClient.predict(items);
            validateBatch(predictions, requested);
        } catch (Unavailable exception) {
            return new MerchantRecognitionRunResponse(items.size(), 0, 0, unsupported, "SERVICE_UNAVAILABLE");
        } catch (InvalidResponse | IllegalArgumentException exception) {
            return new MerchantRecognitionRunResponse(items.size(), 0, 0, unsupported, "INVALID_RESPONSE");
        }

        if (predictions.stream().anyMatch(this::wrongModel)) {
            return new MerchantRecognitionRunResponse(items.size(), 0, 0, unsupported, "MODEL_VERSION_MISMATCH");
        }

        int suggested = 0;
        int unrecognized = 0;
        for (Prediction prediction : predictions) {
            if ("UNRECOGNIZED".equals(prediction.status())) {
                unrecognized++;
                continue;
            }
            Candidate candidate = requested.get(prediction.requestKey());
            if (persistence.persistPending(userId, candidate.transactionId(), prediction)) suggested++;
        }
        return new MerchantRecognitionRunResponse(
                items.size(), suggested, unrecognized, unsupported,
                suggested > 0 ? "SUGGESTED" : "UNRECOGNIZED");
    }

    private RequestItem request(Candidate candidate, Map<String, Candidate> requested) {
        String key = "merchant-" + (requested.size() + 1);
        requested.put(key, candidate);
        return new RequestItem(
                key, sanitizer.sanitize(candidate.recognizedMerchant(), candidate.normalizedDescription()),
                candidate.direction(), candidate.currency(), properties.expectedFeatureVersion());
    }

    private void validateBatch(List<Prediction> predictions, Map<String, Candidate> requested) {
        if (predictions.size() != requested.size()) throw new InvalidResponse("Unexpected merchant response size");
        Set<String> responseKeys = predictions.stream().map(Prediction::requestKey).collect(java.util.stream.Collectors.toSet());
        if (!responseKeys.equals(requested.keySet())) throw new InvalidResponse("Unexpected merchant response keys");
        for (Prediction prediction : predictions) {
            if ("UNRECOGNIZED".equals(prediction.status())) continue;
            if (!"RECOGNIZED".equals(prediction.status())
                    || prediction.canonicalMerchant() == null || prediction.canonicalMerchant().isBlank()
                    || prediction.canonicalMerchant().length() > 160
                    || !MerchantRecognitionPersistence.ALLOWED_CATEGORY_CODES.contains(prediction.categoryCode())
                    || !METHODS.contains(prediction.matchMethod())
                    || prediction.matchScore() == null
                    || prediction.matchScore().compareTo(BigDecimal.ZERO) < 0
                    || prediction.matchScore().compareTo(BigDecimal.ONE) > 0
                    || prediction.matchScore().scale() > 4
                    || !prediction.experimental() || !prediction.syntheticTrainingData() || prediction.guaranteed()) {
                throw new InvalidResponse("Invalid merchant prediction");
            }
        }
    }

    private boolean wrongModel(Prediction prediction) {
        return !properties.expectedModelId().equals(prediction.modelId())
                || !properties.expectedModelVersion().equals(prediction.modelVersion())
                || !properties.expectedFeatureVersion().equals(prediction.featureVersion());
    }
}
