package com.azizx25.smartfinancialwellness.category;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationRunResponse;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPrediction;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryPredictionRepository;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationRule;
import com.azizx25.smartfinancialwellness.category.persistence.CategorizationRuleRepository;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistory;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistoryRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeterministicCategorizationService {

    private static final Comparator<CategorizationRule> PRECEDENCE = Comparator
            .comparing((CategorizationRule rule) -> rule.getMatchType() != CategorizationRule.MatchType.EXACT)
            .thenComparing(CategorizationRule::getPriority, Comparator.reverseOrder())
            .thenComparing(rule -> rule.getNormalizedPattern().length(), Comparator.reverseOrder())
            .thenComparing(CategorizationRule::getCode);

    private final TransactionRecordRepository transactionRepository;
    private final CategorizationRuleRepository ruleRepository;
    private final CategoryPredictionRepository predictionRepository;
    private final TransactionCategoryHistoryRepository historyRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DeterministicCategorizationService(
            TransactionRecordRepository transactionRepository,
            CategorizationRuleRepository ruleRepository,
            CategoryPredictionRepository predictionRepository,
            TransactionCategoryHistoryRepository historyRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.ruleRepository = ruleRepository;
        this.predictionRepository = predictionRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public CategorizationRunResponse categorizeUncategorized(UUID userId) {
        List<TransactionRecord> transactions = transactionRepository.findUncategorizedForUpdate(
                userId, TransactionRecord.UNCATEGORIZED_CATEGORY_ID);
        List<CategorizationRule> rules = ruleRepository.findByActiveTrue().stream()
                .sorted(PRECEDENCE)
                .toList();
        AppUser actor = userRepository.getReferenceById(userId);
        int categorized = 0;
        Instant now = clock.instant();

        for (TransactionRecord transaction : transactions) {
            CategorizationRule rule = rules.stream()
                    .filter(candidate -> matches(candidate, transaction))
                    .findFirst()
                    .orElse(null);
            if (rule == null) continue;

            CategoryPrediction prediction = predictionRepository.saveAndFlush(
                    new CategoryPrediction(transaction, rule.getCategory(), rule.getCode(), now));
            transaction.assignByRule(rule.getCategory(), prediction.getId(), rule.getId(), now);
            historyRepository.save(new TransactionCategoryHistory(
                    transaction,
                    null,
                    rule.getCategory().getId(),
                    "UNCATEGORIZED",
                    "RULES",
                    rule.getId(),
                    actor,
                    now));
            categorized++;
        }
        if (categorized > 0) {
            auditService.record(userId, "DETERMINISTIC_CATEGORIZATION_COMPLETED", "USER", userId);
        }
        return new CategorizationRunResponse(categorized, transactions.size() - categorized);
    }

    private boolean matches(CategorizationRule rule, TransactionRecord transaction) {
        if (rule.getDirection() != null
                && !rule.getDirection().name().equals(transaction.getDirection().name())) {
            return false;
        }
        String evidence = switch (rule.getMatchField()) {
            case DESCRIPTION -> transaction.getNormalizedDescription();
            case COUNTERPARTY -> transaction.getRecognizedMerchant();
        };
        String normalized = normalize(evidence);
        return switch (rule.getMatchType()) {
            case EXACT -> normalized.equals(rule.getNormalizedPattern());
            case CONTAINS -> normalized.contains(rule.getNormalizedPattern());
        };
    }

    static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }
}
