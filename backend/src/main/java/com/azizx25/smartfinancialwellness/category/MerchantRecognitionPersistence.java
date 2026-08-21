package com.azizx25.smartfinancialwellness.category;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantSuggestionResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryResponse;
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
    private final AuditService auditService;
    private final Clock clock;

    public MerchantRecognitionPersistence(
            TransactionRecordRepository transactionRepository,
            CategoryPredictionRepository predictionRepository,
            CategoryRepository categoryRepository,
            TransactionCategoryHistoryRepository historyRepository,
            AuditService auditService,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.predictionRepository = predictionRepository;
        this.categoryRepository = categoryRepository;
        this.historyRepository = historyRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Candidate> loadCandidates(UUID userId, int limit) {
        return transactionRepository.findMerchantRecognitionCandidates(
                        userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID, PageRequest.of(0, limit))
                .stream()
                .map(transaction -> new Candidate(
                        transaction.getId(), transaction.getRecognizedMerchant(),
                        transaction.getNormalizedDescription(), transaction.getDirection().name(),
                        transaction.getCurrency()))
                .toList();
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
        predictionRepository.save(new CategoryPrediction(
                transaction, category, prediction.canonicalMerchant(), prediction.matchScore(),
                prediction.matchMethod(), prediction.modelId(), prediction.modelVersion(),
                prediction.featureVersion(), clock.instant()));
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
        transaction.assignByMl(prediction.getCategory(), prediction.getId(), now);
        historyRepository.save(new TransactionCategoryHistory(
                transaction, null, prediction.getCategory().getId(), "UNCATEGORIZED", "ML",
                null, transaction.getUser(), now));
        auditService.record(userId, "MERCHANT_CATEGORY_SUGGESTION_ACCEPTED", "TRANSACTION", transactionId);
        return categoryResponse(transaction);
    }

    @Transactional
    public void reject(UUID userId, UUID transactionId, UUID predictionId) {
        CategoryPrediction prediction = predictionRepository
                .findOwnedPendingForUpdate(predictionId, transactionId, userId)
                .orElseThrow(CategoryException::suggestionNotFound);
        prediction.reject(clock.instant());
        auditService.record(userId, "MERCHANT_CATEGORY_SUGGESTION_REJECTED", "TRANSACTION", transactionId);
    }

    static MerchantSuggestionResponse suggestion(CategoryPrediction prediction) {
        Category category = prediction.getCategory();
        return new MerchantSuggestionResponse(
                prediction.getId(), prediction.getTransactionId(), category.getId(), category.getCode(),
                category.getLabel(), prediction.getCanonicalMerchant(), prediction.getExplanation(),
                prediction.getProducedAt());
    }

    private TransactionCategoryResponse categoryResponse(TransactionRecord transaction) {
        Category category = transaction.getCategory();
        return new TransactionCategoryResponse(
                transaction.getId(), category.getId(), category.getLabel(), category.getNature().name(),
                "ML", transaction.getCategorizedAt());
    }

    public record Candidate(
            UUID transactionId,
            String recognizedMerchant,
            String normalizedDescription,
            String direction,
            String currency) {
    }
}
