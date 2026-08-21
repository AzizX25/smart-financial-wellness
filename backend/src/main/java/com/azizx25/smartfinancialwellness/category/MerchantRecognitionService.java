package com.azizx25.smartfinancialwellness.category;

import java.math.BigDecimal;
import java.net.URI;
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
    private static final Set<String> MODEL_METHODS = Set.of("EXACT_ALIAS", "FUZZY_ALIAS", "ML");
    private static final Set<String> FALLBACK_REASONS = Set.of(
            "UNSUPPORTED_TRANSACTION", "ENRICHMENT_DISABLED", "ENRICHMENT_NOT_CONFIGURED",
            "MODEL_ABSTAINED", "INVALID_EVIDENCE", "ENRICHMENT_UNAVAILABLE",
            "ENRICHMENT_INVALID_RESPONSE");

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
            if ("UNRECOGNIZED".equals(prediction.status())) {
                validateAbstention(prediction);
                continue;
            }
            if (!"RECOGNIZED".equals(prediction.status()) || invalidCommonSuggestion(prediction)) {
                throw new InvalidResponse("Invalid merchant prediction");
            }
            if ("WEB_LLM".equals(prediction.matchMethod())) {
                validateWebSuggestion(prediction);
            } else {
                validateModelSuggestion(prediction);
            }
        }
    }

    private boolean invalidCommonSuggestion(Prediction prediction) {
        return prediction.canonicalMerchant() == null || prediction.canonicalMerchant().isBlank()
                || prediction.canonicalMerchant().length() > 160
                || !MerchantRecognitionPersistence.ALLOWED_CATEGORY_CODES.contains(prediction.categoryCode())
                || !prediction.experimental() || prediction.guaranteed();
    }

    private void validateModelSuggestion(Prediction prediction) {
        if (!MODEL_METHODS.contains(prediction.matchMethod())
                || prediction.matchScore() == null
                || prediction.matchScore().compareTo(BigDecimal.ZERO) < 0
                || prediction.matchScore().compareTo(BigDecimal.ONE) > 0
                || prediction.matchScore().scale() > 4
                || !prediction.syntheticTrainingData()
                || prediction.providerId() != null
                || prediction.rationale() != null
                || !prediction.evidenceUrls().isEmpty()) {
            throw new InvalidResponse("Invalid merchant model prediction");
        }
    }

    private void validateWebSuggestion(Prediction prediction) {
        if (prediction.matchScore() != null
                || prediction.syntheticTrainingData()
                || prediction.rationale() == null || prediction.rationale().isBlank()
                || prediction.rationale().length() > 300
                || prediction.evidenceUrls().isEmpty() || prediction.evidenceUrls().size() > 3
                || prediction.evidenceUrls().stream().distinct().count() != prediction.evidenceUrls().size()
                || prediction.evidenceUrls().stream().anyMatch(this::invalidEvidenceUrl)) {
            throw new InvalidResponse("Invalid merchant enrichment prediction");
        }
    }

    private void validateAbstention(Prediction prediction) {
        if (prediction.canonicalMerchant() != null || prediction.categoryCode() != null
                || prediction.matchMethod() != null || prediction.matchScore() != null
                || prediction.rationale() != null || !prediction.evidenceUrls().isEmpty()
                || prediction.providerId() != null || prediction.catalogVersion() != null
                || prediction.taxonomyVersion() != null
                || prediction.fallbackReason() == null
                || !FALLBACK_REASONS.contains(prediction.fallbackReason())
                || !prediction.experimental() || !prediction.syntheticTrainingData() || prediction.guaranteed()) {
            throw new InvalidResponse("Invalid merchant abstention");
        }
    }

    private boolean invalidEvidenceUrl(String value) {
        if (value == null || value.length() > 2048) return true;
        try {
            URI uri = URI.create(value);
            return !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null;
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private boolean wrongModel(Prediction prediction) {
        if ("WEB_LLM".equals(prediction.matchMethod())) {
            return !properties.expectedEnrichmentProviderId().equals(prediction.providerId())
                    || !properties.expectedEnrichmentModelId().equals(prediction.modelId())
                    || !properties.expectedEnrichmentModelVersion().equals(prediction.modelVersion())
                    || !properties.expectedEnrichmentFeatureVersion().equals(prediction.featureVersion())
                    || !properties.expectedCatalogVersion().equals(prediction.catalogVersion())
                    || !properties.expectedTaxonomyVersion().equals(prediction.taxonomyVersion());
        }
        return !properties.expectedModelId().equals(prediction.modelId())
                || !properties.expectedModelVersion().equals(prediction.modelVersion())
                || !properties.expectedFeatureVersion().equals(prediction.featureVersion());
    }
}
