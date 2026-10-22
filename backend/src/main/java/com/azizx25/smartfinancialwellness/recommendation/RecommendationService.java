package com.azizx25.smartfinancialwellness.recommendation;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.FeedbackStatus;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.GenerationResponse;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.PageResponse;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.RecommendationResponse;
import com.azizx25.smartfinancialwellness.recommendation.persistence.RecommendationRepository;
import com.azizx25.smartfinancialwellness.score.FinancialScoreException;
import com.azizx25.smartfinancialwellness.score.FinancialScoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private final FinancialScoreService scores;
    private final RecommendationRules rules;
    private final RecommendationRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public RecommendationService(FinancialScoreService scores, RecommendationRules rules,
            RecommendationRepository repository, AuditService audit, Clock clock) {
        this.scores = scores;
        this.rules = rules;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GenerationResponse generate(UUID userId, String currency) {
        requireCurrency(currency);
        repository.lockOwner(userId);
        var score = scores.calculateCurrent(userId, currency);
        if (!score.available()) {
            return new GenerationResponse("INSUFFICIENT_DATA", currency, null, RecommendationRules.VERSION, List.of());
        }
        repository.obsoletePrevious(userId, score.id(), currency);
        boolean created = false;
        for (var proposal : rules.select(score.factors())) {
            created |= repository.create(userId, score.id(), proposal, clock.instant());
        }
        if (created) audit.record(userId, "RECOMMENDATIONS_GENERATED", "WELLNESS_EVALUATION", score.id());
        var items = repository.forEvaluation(userId, score.id());
        return new GenerationResponse(items.isEmpty() ? "NO_ACTION_NEEDED" : "AVAILABLE",
                currency, score.id(), RecommendationRules.VERSION, items);
    }

    @Transactional(readOnly = true)
    public PageResponse list(UUID userId, String currency, int page, int size) {
        requireCurrency(currency);
        long count = repository.count(userId, currency);
        return new PageResponse(repository.list(userId, currency, page, size), page, size,
                count, (int) ((count + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(UUID userId, UUID id) { return repository.owned(userId, id); }

    @Transactional
    public RecommendationResponse feedback(UUID userId, UUID id, FeedbackStatus status) {
        repository.lockOwner(userId);
        var current = repository.owned(userId, id);
        if (current.status().equals(status.name())) return current;
        if (!"ACTIVE".equals(current.status())) throw RecommendationException.closed();
        repository.feedback(id, status.name(), clock.instant());
        audit.record(userId, "RECOMMENDATION_" + status.name(), "RECOMMENDATION", id);
        return repository.owned(userId, id);
    }

    private void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw FinancialScoreException.invalid("Currency must contain three uppercase letters");
        }
    }
}
