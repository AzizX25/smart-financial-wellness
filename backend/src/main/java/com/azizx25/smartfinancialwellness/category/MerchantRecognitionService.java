package com.azizx25.smartfinancialwellness.category;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.MerchantRecognitionPersistence.Candidate;
import com.azizx25.smartfinancialwellness.category.CategorizationReviewService.MemoryMatch;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
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
    private final CategorizationReviewService reviewService;
    private final MerchantRecognitionAiClient aiClient;
    private final MerchantAiClientProperties properties;

    public MerchantRecognitionService(
            DeterministicCategorizationService deterministicService,
            MerchantRecognitionPersistence persistence,
            CategorizationReviewService reviewService,
            MerchantRecognitionAiClient aiClient,
            MerchantAiClientProperties properties) {
        this.deterministicService = deterministicService;
        this.persistence = persistence;
        this.reviewService = reviewService;
        this.aiClient = aiClient;
        this.properties = properties;
    }

    public MerchantRecognitionRunResponse generate(UUID userId) {
        deterministicService.categorizeUncategorized(userId);
        List<Candidate> candidates = persistence.loadCandidates(userId, BATCH_LIMIT);
        int unsupported = (int) candidates.stream()
                .filter(candidate -> "UNSUPPORTED_CURRENCY".equals(candidate.exclusionReason())).count();
        int excluded = 0;
        int suggested = 0;
        int reviewRequired = 0;
        Map<String, Candidate> requested = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.pendingSuggestion()) continue;
            if (candidate.exclusionReason() != null) {
                persistence.persistExcluded(userId, candidate.transactionId(), candidate.exclusionReason());
                excluded++;
                continue;
            }
            if (candidate.merchantKey().isBlank()) {
                persistence.persistNeedsReview(
                        userId, candidate.transactionId(), "MERCHANT_TEXT_UNAVAILABLE", List.of());
                reviewRequired++;
                continue;
            }
            MemoryMatch memory = reviewService.findMemory(
                    userId, candidate.merchantKey(),
                    TransactionRecord.Direction.valueOf(candidate.direction()), candidate.currency());
            if (memory != null && persistence.persistMemoryPending(
                    userId, candidate.transactionId(), memory)) {
                suggested++;
                continue;
            }
            request(candidate, requested);
        }
        List<RequestItem> items = requested.entrySet().stream()
                .map(entry -> new RequestItem(
                        entry.getKey(), entry.getValue().merchantKey(), entry.getValue().direction(),
                        entry.getValue().currency(), properties.expectedFeatureVersion()))
                .toList();
        if (items.isEmpty()) {
            return new MerchantRecognitionRunResponse(
                    0, suggested, 0, unsupported, reviewRequired, excluded,
                    suggested > 0 ? "SUGGESTED"
                            : unsupported > 0 ? "UNSUPPORTED_CURRENCY" : "UNRECOGNIZED");
        }

        List<Prediction> predictions;
        try {
            predictions = aiClient.predict(items);
            validateBatch(predictions, requested);
        } catch (Unavailable exception) {
            persistence.persistBatchReview(userId, requested, "SERVICE_UNAVAILABLE");
            return new MerchantRecognitionRunResponse(
                    items.size(), suggested, 0, unsupported,
                    reviewRequired + items.size(), excluded, "SERVICE_UNAVAILABLE");
        } catch (InvalidResponse | IllegalArgumentException exception) {
            persistence.persistBatchReview(userId, requested, "INVALID_RESPONSE");
            return new MerchantRecognitionRunResponse(
                    items.size(), suggested, 0, unsupported,
                    reviewRequired + items.size(), excluded, "INVALID_RESPONSE");
        }

        if (predictions.stream().anyMatch(this::wrongModel)) {
            persistence.persistBatchReview(userId, requested, "MODEL_VERSION_MISMATCH");
            return new MerchantRecognitionRunResponse(
                    items.size(), suggested, 0, unsupported,
                    reviewRequired + items.size(), excluded, "MODEL_VERSION_MISMATCH");
        }

        int unrecognized = 0;
        for (Prediction prediction : predictions) {
            if ("UNRECOGNIZED".equals(prediction.status())) {
                unrecognized++;
                reviewRequired++;
            }
        }
        suggested += persistence.persistAiBatch(userId, requested, predictions);
        return new MerchantRecognitionRunResponse(
                items.size(), suggested, unrecognized, unsupported, reviewRequired, excluded,
                suggested > 0 ? "SUGGESTED" : "UNRECOGNIZED");
    }

    private void request(Candidate candidate, Map<String, Candidate> requested) {
        String key = "merchant-" + (requested.size() + 1);
        requested.put(key, candidate);
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
                || !prediction.experimental() || prediction.guaranteed()
                || !prediction.candidateCategoryCodes().isEmpty();
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
                || prediction.candidateCategoryCodes().size() > 3
                || prediction.candidateCategoryCodes().stream().distinct().count()
                        != prediction.candidateCategoryCodes().size()
                || prediction.candidateCategoryCodes().stream()
                        .anyMatch(code -> !MerchantRecognitionPersistence.ALLOWED_CATEGORY_CODES.contains(code))
                || (!prediction.candidateCategoryCodes().isEmpty()
                        && !"MODEL_ABSTAINED".equals(prediction.fallbackReason()))
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
