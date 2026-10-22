package com.azizx25.smartfinancialwellness.category;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantSuggestionResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryResponse;
import com.azizx25.smartfinancialwellness.category.CategorizationReviewService.MemoryMatch;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient.Prediction;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction.ReviewStatus;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPredictionRepository;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistory;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MerchantRecognitionPersistence {

    static final Set<String> ALLOWED_CATEGORY_CODES = Set.of(
            "FOOD", "BILLS_SUBSCRIPTIONS", "TRANSPORT", "HEALTH", "SHOPPING",
            "LEISURE", "EDUCATION", "HOUSING", "BANK_FEES", "TAXES");

    private final TransactionRecordRepository transactionRepository;
    private final CategoryPredictionRepository predictionRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionCategoryHistoryRepository historyRepository;
    private final CategorizationReviewService reviewService;
    private final AuditService auditService;
    private final Clock clock;

    public MerchantRecognitionPersistence(
            TransactionRecordRepository transactionRepository,
            CategoryPredictionRepository predictionRepository,
            CategoryRepository categoryRepository,
            TransactionCategoryHistoryRepository historyRepository,
            CategorizationReviewService reviewService,
            AuditService auditService,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.predictionRepository = predictionRepository;
        this.categoryRepository = categoryRepository;
        this.historyRepository = historyRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Candidate> loadCandidates(UUID userId, int limit) {
        List<TransactionRecord> transactions = transactionRepository.findUncategorizedForReview(
                        userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID, PageRequest.of(0, limit))
                .getContent();
        Set<UUID> transfers = reviewService.confirmedTransfers(
                transactions.stream().map(TransactionRecord::getId).toList());
        return transactions.stream()
                .map(transaction -> new Candidate(
                        transaction.getId(), transaction.getRecognizedMerchant(),
                        transaction.getNormalizedDescription(), transaction.getDirection().name(),
                        transaction.getCurrency(), reviewService.merchantKey(transaction),
                        reviewService.exclusionReason(transaction, transfers.contains(transaction.getId())),
                        predictionRepository.existsByTransaction_IdAndReviewStatus(
                                transaction.getId(), ReviewStatus.PENDING), transaction.getVersion()))
                .toList();
    }

    @Transactional
    public void persistExcluded(UUID userId, UUID transactionId, String reasonCode) {
        TransactionRecord transaction = ownedUncategorized(userId, transactionId);
        if (transaction != null) reviewService.recordExcluded(transaction, reasonCode);
    }

    @Transactional
    public void persistNeedsReview(
            UUID userId, UUID transactionId, String reasonCode, List<String> candidateCodes) {
        TransactionRecord transaction = ownedUncategorized(userId, transactionId);
        if (transaction != null
                && !predictionRepository.existsByTransaction_IdAndReviewStatus(
                        transactionId, ReviewStatus.PENDING)) {
            reviewService.recordNeedsReview(transaction, reasonCode, candidateCodes);
        }
    }

    @Transactional
    public void persistBatchReview(
            UUID userId, Map<String, Candidate> requested, String reasonCode) {
        requested.values().stream()
                .sorted(java.util.Comparator.comparing(Candidate::transactionId))
                .filter(candidate -> unchanged(userId, candidate))
                .forEach(candidate -> persistNeedsReview(
                        userId, candidate.transactionId(), reasonCode, List.of()));
    }

    @Transactional
    public int persistAiBatch(
            UUID userId, Map<String, Candidate> requested, List<Prediction> predictions) {
        int suggested = 0;
        List<Prediction> ordered = predictions.stream()
                .sorted(java.util.Comparator.comparing(
                        prediction -> requested.get(prediction.requestKey()).transactionId()))
                .toList();
        for (Prediction prediction : ordered) {
            Candidate candidate = requested.get(prediction.requestKey());
            if (!unchanged(userId, candidate)) continue;
            if ("UNRECOGNIZED".equals(prediction.status())) {
                persistNeedsReview(
                        userId, candidate.transactionId(), prediction.fallbackReason(),
                        prediction.candidateCategoryCodes());
            } else if (persistPending(userId, candidate.transactionId(), prediction)) {
                suggested++;
            }
        }
        return suggested;
    }

    @Transactional
    public boolean persistMemoryPending(
            UUID userId, Candidate candidate, MemoryMatch memory) {
        UUID transactionId = candidate.transactionId();
        TransactionRecord transaction = transactionRepository.findOwnedByIdForUpdate(transactionId, userId)
                .orElse(null);
        if (transaction == null || transaction.getVersion() != candidate.version()
                || !transactionRepository.isMerchantRecognitionEligible(
                        transactionId, userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID)
                || predictionRepository.existsByTransaction_IdAndReviewStatus(transactionId, ReviewStatus.PENDING)) {
            return false;
        }
        Category category = categoryRepository.findVisibleActiveById(memory.categoryId(), userId).orElse(null);
        if (category == null || (category.getDirection() != Category.Direction.BOTH
                && !category.getDirection().name().equals(transaction.getDirection().name()))) {
            return false;
        }
        CategoryPrediction prediction = CategoryPrediction.fromUserMemory(
                transaction, category, memory.normalizedMerchantKey(), clock.instant());
        predictionRepository.save(prediction);
        reviewService.recordSuggestion(transaction, prediction);
        return true;
    }

    @Transactional
    public boolean persistPending(UUID userId, UUID transactionId, Prediction prediction) {
        TransactionRecord transaction = transactionRepository.findOwnedByIdForUpdate(transactionId, userId)
                .orElse(null);
        if (transaction == null
                || !transactionRepository.isMerchantRecognitionEligible(
                        transactionId, userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID)
                || predictionRepository.existsByTransaction_IdAndReviewStatus(transactionId, ReviewStatus.PENDING)) {
            return false;
        }
        Category category = categoryRepository.findByCodeAndUserIsNull(prediction.categoryCode()).orElse(null);
        if (category == null || !category.isActive() || category.getDirection() != Category.Direction.DEBIT) {
            return false;
        }
        Instant now = clock.instant();
        CategoryPrediction entity = "WEB_LLM".equals(prediction.matchMethod())
                ? new CategoryPrediction(
                        transaction, category, prediction.canonicalMerchant(), prediction.providerId(),
                        prediction.modelId(), prediction.modelVersion(), prediction.featureVersion(),
                        prediction.rationale(), prediction.evidenceUrls(), now)
                : new CategoryPrediction(
                        transaction, category, prediction.canonicalMerchant(), prediction.matchScore(),
                        prediction.matchMethod(), prediction.modelId(), prediction.modelVersion(),
                        prediction.featureVersion(), now);
        predictionRepository.save(entity);
        reviewService.recordSuggestion(transaction, entity);
        return true;
    }

    @Transactional(readOnly = true)
    public List<MerchantSuggestionResponse> listPending(UUID userId) {
        return predictionRepository.findOwnedPending(userId).stream()
                .map(MerchantRecognitionPersistence::suggestion)
                .toList();
    }

    @Transactional
    public TransactionCategoryResponse accept(UUID userId, UUID transactionId, UUID predictionId) {
        TransactionRecord lockedTransaction = transactionRepository
                .findOwnedByIdForUpdate(transactionId, userId)
                .orElseThrow(CategoryException::suggestionNotFound);
        CategoryPrediction prediction = predictionRepository
                .findOwnedPendingForUpdate(predictionId, transactionId, userId)
                .orElseThrow(CategoryException::suggestionNotFound);
        TransactionRecord transaction = prediction.getTransaction();
        if (!transaction.getId().equals(lockedTransaction.getId())) {
            throw CategoryException.suggestionNotFound();
        }
        if (!transactionRepository.isMerchantRecognitionEligible(
                transactionId, userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID)) {
            throw CategoryException.suggestionConflict();
        }
        Instant now = clock.instant();
        prediction.accept(now);
        predictionRepository.flush();
        String source = switch (prediction.getPredictionMethod()) {
            case "LLM" -> "LLM";
            case "USER_HISTORY" -> "USER_MEMORY";
            default -> "ML";
        };
        if ("LLM".equals(source)) {
            transaction.assignByLlm(prediction.getCategory(), prediction.getId(), now);
        } else if ("USER_MEMORY".equals(source)) {
            transaction.assignByUserMemory(prediction.getCategory(), prediction.getId(), now);
        } else {
            transaction.assignByMl(prediction.getCategory(), prediction.getId(), now);
        }
        historyRepository.save(new TransactionCategoryHistory(
                transaction, null, prediction.getCategory().getId(), "UNCATEGORIZED", source,
                null, transaction.getUser(), now));
        reviewService.resolveAccepted(transaction, prediction, now);
        auditService.record(userId, "MERCHANT_CATEGORY_SUGGESTION_ACCEPTED", "TRANSACTION", transactionId);
        return categoryResponse(transaction);
    }

    @Transactional
    public void reject(UUID userId, UUID transactionId, UUID predictionId) {
        TransactionRecord transaction = transactionRepository.findOwnedByIdForUpdate(transactionId, userId)
                .orElseThrow(CategoryException::suggestionNotFound);
        CategoryPrediction prediction = predictionRepository
                .findOwnedPendingForUpdate(predictionId, transactionId, userId)
                .orElseThrow(CategoryException::suggestionNotFound);
        prediction.reject(clock.instant());
        reviewService.rejectSuggestion(transaction, prediction);
        auditService.record(userId, "MERCHANT_CATEGORY_SUGGESTION_REJECTED", "TRANSACTION", transactionId);
    }

    static MerchantSuggestionResponse suggestion(CategoryPrediction prediction) {
        Category category = prediction.getCategory();
        boolean userMemory = "USER_HISTORY".equals(prediction.getPredictionMethod());
        boolean web = "LLM".equals(prediction.getPredictionMethod());
        return new MerchantSuggestionResponse(
                prediction.getId(), prediction.getTransactionId(), category.getId(), category.getCode(),
                category.getLabel(), prediction.getCanonicalMerchant(),
                userMemory ? "USER_MEMORY" : web ? "WEB_LLM" : prediction.getExplanation(),
                userMemory ? "USER_MEMORY" : web ? "WEB_LLM" : "MODEL",
                web ? prediction.getExplanation() : null,
                prediction.getEvidenceUrls(), !userMemory, false,
                prediction.getProducedAt());
    }

    private TransactionCategoryResponse categoryResponse(TransactionRecord transaction) {
        Category category = transaction.getCategory();
        return new TransactionCategoryResponse(
                transaction.getId(), category.getId(), category.getLabel(), category.getNature().name(),
                transaction.getCategorySource(), transaction.getCategorizedAt());
    }

    public record Candidate(
            UUID transactionId,
            String recognizedMerchant,
            String normalizedDescription,
            String direction,
            String currency,
            String merchantKey,
            String exclusionReason,
            boolean pendingSuggestion,
            int version) {
    }

    private boolean unchanged(UUID userId, Candidate candidate) {
        return transactionRepository.findOwnedByIdForUpdate(candidate.transactionId(), userId)
                .filter(transaction -> transaction.getVersion() == candidate.version())
                .isPresent();
    }

    private TransactionRecord ownedUncategorized(UUID userId, UUID transactionId) {
        TransactionRecord transaction = transactionRepository.findOwnedByIdForUpdate(transactionId, userId)
                .orElse(null);
        return transaction != null && "UNCATEGORIZED".equals(transaction.getCategorySource())
                && TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(transaction.getCategory().getId())
                ? transaction : null;
    }
}
