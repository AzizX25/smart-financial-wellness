package com.azizx25.smartfinancialwellness.category;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationReviewPageResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationReviewResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantMemoryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantSuggestionResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.ReviewCandidateResponse;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReview;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReview.Resolution;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReview.State;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReviewCandidate;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReviewCandidateRepository;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationReviewRepository;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPredictionRepository;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.category.persistence.UserMerchantMemory;
import com.azizx25.smartfinancialwellness.category.persistence.UserMerchantMemoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategorizationReviewService {

    private final TransactionRecordRepository transactionRepository;
    private final CategoryPredictionRepository predictionRepository;
    private final CategorizationReviewRepository reviewRepository;
    private final CategorizationReviewCandidateRepository candidateRepository;
    private final UserMerchantMemoryRepository memoryRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantPrivacySanitizer sanitizer;
    private final AuditService auditService;
    private final Clock clock;

    public CategorizationReviewService(
            TransactionRecordRepository transactionRepository,
            CategoryPredictionRepository predictionRepository,
            CategorizationReviewRepository reviewRepository,
            CategorizationReviewCandidateRepository candidateRepository,
            UserMerchantMemoryRepository memoryRepository,
            CategoryRepository categoryRepository,
            MerchantPrivacySanitizer sanitizer,
            AuditService auditService,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.predictionRepository = predictionRepository;
        this.reviewRepository = reviewRepository;
        this.candidateRepository = candidateRepository;
        this.memoryRepository = memoryRepository;
        this.categoryRepository = categoryRepository;
        this.sanitizer = sanitizer;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CategorizationReviewPageResponse list(UUID userId, int page, int size) {
        Page<TransactionRecord> transactions = transactionRepository.findUncategorizedForReview(
                userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID, PageRequest.of(page, size));
        List<UUID> transactionIds = transactions.getContent().stream().map(TransactionRecord::getId).toList();
        Set<UUID> transfers = confirmedTransfers(transactionIds);
        Map<UUID, CategorizationReview> reviews = transactionIds.isEmpty() ? Map.of()
                : reviewRepository.findOwnedOpen(transactionIds, userId).stream()
                        .collect(Collectors.toMap(CategorizationReview::getTransactionId, Function.identity()));
        Map<UUID, CategoryPrediction> pending = predictionRepository.findOwnedPending(userId).stream()
                .filter(prediction -> transactionIds.contains(prediction.getTransactionId()))
                .collect(Collectors.toMap(CategoryPrediction::getTransactionId, Function.identity()));
        Map<UUID, List<CategorizationReviewCandidate>> candidates = candidatesByReview(reviews.values());

        List<CategorizationReviewResponse> items = transactions.getContent().stream()
                .map(transaction -> response(
                        userId, transaction, reviews.get(transaction.getId()),
                        pending.get(transaction.getId()), candidates, transfers))
                .toList();
        return new CategorizationReviewPageResponse(
                items, transactions.getNumber(), transactions.getSize(), transactions.getTotalElements(),
                transactions.getTotalPages(), transactions.isFirst(), transactions.isLast());
    }

    @Transactional(readOnly = true)
    public List<MerchantMemoryResponse> listMemories(UUID userId) {
        return memoryRepository.findOwned(userId).stream().map(this::memoryResponse).toList();
    }

    @Transactional(readOnly = true)
    public MemoryMatch findMemory(
            UUID userId, String merchantKey, TransactionRecord.Direction direction, String currency) {
        if (merchantKey.isBlank()) return null;
        return memoryRepository.findActiveMatch(userId, merchantKey, direction, currency)
                .map(memory -> new MemoryMatch(
                        memory.getId(), memory.getCategory().getId(), memory.getNormalizedMerchantKey()))
                .orElse(null);
    }

    @Transactional
    public void forget(UUID userId, UUID memoryId) {
        UserMerchantMemory memory = memoryRepository.findByIdAndUser_Id(memoryId, userId)
                .orElseThrow(CategoryException::memoryNotFound);
        memoryRepository.delete(memory);
        memoryRepository.flush();
        auditService.record(userId, "MERCHANT_MEMORY_DELETED", "MERCHANT_MEMORY", memoryId);
    }

    public String merchantKey(TransactionRecord transaction) {
        return sanitizer.sanitize(transaction.getRecognizedMerchant(), transaction.getNormalizedDescription());
    }

    public String exclusionReason(TransactionRecord transaction, boolean confirmedTransfer) {
        if (transaction.getSourceType() == TransactionRecord.SourceType.BANK_SYNC
                && "REVERSED".equals(transaction.getProviderStatus())) return "REVERSED";
        if (confirmedTransfer) return "INTERNAL_TRANSFER";
        if (transaction.getSourceType() == TransactionRecord.SourceType.BANK_SYNC
                && !"POSTED".equals(transaction.getProviderStatus())) return "NOT_POSTED";
        if (!"TND".equals(transaction.getCurrency())) return "UNSUPPORTED_CURRENCY";
        if (transaction.getDirection() == TransactionRecord.Direction.CREDIT) return "CREDIT_NOT_SUPPORTED";
        return null;
    }

    @Transactional
    public void recordSuggestion(TransactionRecord transaction, CategoryPrediction prediction) {
        CategorizationReview review = openReview(
                transaction, State.SUGGESTION_PENDING, "RELIABLE_SUGGESTION", prediction.getId());
        clearCandidates(review);
    }

    @Transactional
    public void recordNeedsReview(
            TransactionRecord transaction, String reasonCode, List<String> candidateCodes) {
        CategorizationReview review = openReview(
                transaction, State.NEEDS_REVIEW, normalizeReason(reasonCode), null);
        replaceCandidates(review, candidateCodes);
    }

    @Transactional
    public void recordExcluded(TransactionRecord transaction, String reasonCode) {
        CategorizationReview review = openReview(
                transaction, State.EXCLUDED, normalizeReason(reasonCode), null);
        clearCandidates(review);
    }

    @Transactional
    public void rejectSuggestion(TransactionRecord transaction, CategoryPrediction prediction) {
        CategorizationReview review = openReview(
                transaction, State.NEEDS_REVIEW, "SUGGESTION_REJECTED", prediction.getId());
        clearCandidates(review);
    }

    @Transactional
    public void resolveAccepted(TransactionRecord transaction, CategoryPrediction prediction, Instant now) {
        CategorizationReview review = reviewRepository
                .findOwnedOpenForUpdate(transaction.getId(), transaction.getUserId())
                .orElseGet(() -> new CategorizationReview(
                        transaction, State.SUGGESTION_PENDING, "RELIABLE_SUGGESTION", prediction.getId(), now));
        review.resolve(Resolution.SUGGESTION_ACCEPTED, prediction.getCategory(), prediction.getId(), now);
        reviewRepository.save(review);
        remember(transaction, prediction.getCategory(), now);
        auditService.record(
                transaction.getUserId(), "CATEGORIZATION_REVIEW_RESOLVED", "CATEGORIZATION_REVIEW", review.getId());
    }

    @Transactional
    public void resolveManual(TransactionRecord transaction, Category category, Instant now) {
        CategorizationReview review = reviewRepository
                .findOwnedOpenForUpdate(transaction.getId(), transaction.getUserId())
                .orElseGet(() -> new CategorizationReview(
                        transaction, State.NEEDS_REVIEW, "MANUAL_SELECTION", null, now));
        review.resolve(Resolution.MANUAL_ASSIGNMENT, category, null, now);
        reviewRepository.save(review);
        remember(transaction, category, now);
        auditService.record(
                transaction.getUserId(), "CATEGORIZATION_REVIEW_RESOLVED", "CATEGORIZATION_REVIEW", review.getId());
    }

    @Transactional
    public void reopenAfterRemoval(TransactionRecord transaction) {
        CategorizationReview review = openReview(
                transaction, State.NEEDS_REVIEW, "CATEGORY_REMOVED", null);
        clearCandidates(review);
        forgetForTransaction(transaction);
    }

    public Set<UUID> confirmedTransfers(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) return Set.of();
        return Set.copyOf(transactionRepository.findConfirmedInternalTransferTransactionIds(transactionIds));
    }

    private CategorizationReview openReview(
            TransactionRecord transaction, State state, String reason, UUID predictionId) {
        CategorizationReview review = reviewRepository
                .findOwnedOpenForUpdate(transaction.getId(), transaction.getUserId())
                .orElseGet(() -> new CategorizationReview(
                        transaction, state, reason, predictionId, clock.instant()));
        review.updateOpen(state, reason, predictionId);
        return reviewRepository.save(review);
    }

    private void replaceCandidates(CategorizationReview review, List<String> candidateCodes) {
        clearCandidates(review);
        short rank = 1;
        Instant now = clock.instant();
        for (String code : candidateCodes.stream().distinct().limit(3).toList()) {
            Category category = categoryRepository.findByCodeAndUserIsNull(code).orElse(null);
            if (category != null && category.isActive()
                    && MerchantRecognitionPersistence.ALLOWED_CATEGORY_CODES.contains(code)) {
                candidateRepository.save(new CategorizationReviewCandidate(review, category, rank++, now));
            }
        }
    }

    private void clearCandidates(CategorizationReview review) {
        candidateRepository.deleteByReview_Id(review.getId());
        candidateRepository.flush();
    }

    private void remember(TransactionRecord transaction, Category category, Instant now) {
        String key = merchantKey(transaction);
        if (key.isBlank()) return;
        UserMerchantMemory existing = memoryRepository
                .findByUser_IdAndNormalizedMerchantKeyAndDirectionAndCurrency(
                        transaction.getUserId(), key, transaction.getDirection(), transaction.getCurrency())
                .orElse(null);
        if (existing == null) {
            UserMerchantMemory created = memoryRepository.save(new UserMerchantMemory(
                    transaction.getUser(), key, transaction.getDirection(), transaction.getCurrency(), category, now));
            auditService.record(
                    transaction.getUserId(), "MERCHANT_MEMORY_CREATED", "MERCHANT_MEMORY", created.getId());
        } else if (!existing.getCategory().getId().equals(category.getId())) {
            existing.replaceCategory(category);
            auditService.record(
                    transaction.getUserId(), "MERCHANT_MEMORY_UPDATED", "MERCHANT_MEMORY", existing.getId());
        }
    }

    public void forgetForTransaction(TransactionRecord transaction) {
        String key = merchantKey(transaction);
        if (key.isBlank()) return;
        memoryRepository.findByUser_IdAndNormalizedMerchantKeyAndDirectionAndCurrency(
                        transaction.getUserId(), key, transaction.getDirection(), transaction.getCurrency())
                .ifPresent(memory -> {
                    UUID memoryId = memory.getId();
                    memoryRepository.delete(memory);
                    auditService.record(
                            transaction.getUserId(), "MERCHANT_MEMORY_DELETED", "MERCHANT_MEMORY", memoryId);
                });
    }

    private CategorizationReviewResponse response(
            UUID userId,
            TransactionRecord transaction,
            CategorizationReview review,
            CategoryPrediction prediction,
            Map<UUID, List<CategorizationReviewCandidate>> candidates,
            Set<UUID> transfers) {
        String state;
        String reason;
        UUID reviewId = review == null ? null : review.getId();
        MerchantSuggestionResponse suggestion = null;
        List<ReviewCandidateResponse> categoryCandidates = List.of();
        if (prediction != null) {
            state = "SUGGESTION_PENDING";
            reason = "RELIABLE_SUGGESTION";
            suggestion = MerchantRecognitionPersistence.suggestion(prediction);
        } else if (review != null) {
            state = review.getState().name();
            reason = review.getReasonCode();
            categoryCandidates = candidates.getOrDefault(review.getId(), List.of()).stream()
                    .filter(candidate -> visibleTo(userId, candidate.getCategory()))
                    .map(candidate -> new ReviewCandidateResponse(
                            candidate.getCandidateRank(), candidate.getCategory().getId(),
                            candidate.getCategory().getCode(), candidate.getCategory().getLabel()))
                    .toList();
        } else {
            reason = exclusionReason(transaction, transfers.contains(transaction.getId()));
            state = reason == null ? "NEEDS_REVIEW" : "EXCLUDED";
            if (reason == null) reason = "NOT_ANALYZED";
        }
        return new CategorizationReviewResponse(
                reviewId, transaction.getId(), transaction.getBookingDate(), transaction.getDescription(),
                transaction.getRecognizedMerchant(), transaction.getDirection().name(), transaction.getAmount(),
                transaction.getCurrency(), transaction.getProviderStatus(), state, reason, suggestion,
                categoryCandidates, true);
    }

    private Map<UUID, List<CategorizationReviewCandidate>> candidatesByReview(
            Collection<CategorizationReview> reviews) {
        if (reviews.isEmpty()) return Map.of();
        return candidateRepository.findByReview_IdInOrderByReview_IdAscCandidateRankAsc(
                        reviews.stream().map(CategorizationReview::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        CategorizationReviewCandidate::getReviewId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private boolean visibleTo(UUID userId, Category category) {
        return category.isActive()
                && (category.getUserId() == null || userId.equals(category.getUserId()));
    }

    private MerchantMemoryResponse memoryResponse(UserMerchantMemory memory) {
        Category category = memory.getCategory();
        return new MerchantMemoryResponse(
                memory.getId(), memory.getNormalizedMerchantKey(), memory.getDirection().name(),
                memory.getCurrency(), category.getId(), category.getLabel(),
                memory.getCreatedAt(), memory.getUpdatedAt());
    }

    private String normalizeReason(String value) {
        return value == null || value.isBlank() ? "NO_RELIABLE_SUGGESTION" : value;
    }

    public record MemoryMatch(UUID memoryId, UUID categoryId, String normalizedMerchantKey) {
    }
}
