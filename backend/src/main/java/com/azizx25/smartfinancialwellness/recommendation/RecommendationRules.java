package com.azizx25.smartfinancialwellness.recommendation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.FactorResponse;
import org.springframework.stereotype.Component;

/** Versioned educational selection rules. They never alter financial facts. */
@Component
public class RecommendationRules {
    public static final String VERSION = "financial-recommendations-v1";

    public List<Proposal> select(List<FactorResponse> factors) {
        List<Proposal> proposals = new ArrayList<>();
        for (FactorResponse factor : factors) {
            if (!factor.available() || factor.rawValue() == null) continue;
            BigDecimal value = factor.rawValue();
            switch (factor.code()) {
                case "SAVINGS_RATE" -> {
                    if (value.compareTo(new BigDecimal("0.1000")) < 0) {
                        proposals.add(new Proposal("SAVINGS_MARGIN", factor.code(),
                                value.signum() <= 0 ? "HIGH" : "MEDIUM",
                                "Retrouver une marge d’épargne",
                                "Vos dépenses laissent une marge limitée sur la période analysée. "
                                        + "Comparez vos dépenses réelles et votre budget avant de fixer un montant d’épargne.",
                                "/planification-salaire", "Revoir mon budget"));
                    }
                }
                case "INCOME_STABILITY" -> {
                    if (value.compareTo(new BigDecimal("0.1500")) >= 0) {
                        proposals.add(new Proposal("INCOME_VARIATION", factor.code(), "MEDIUM",
                                "Prévoir des revenus variables",
                                "Les revenus ont varié sur la période analysée. "
                                        + "Vérifiez vos revenus attendus avant de répartir votre budget.",
                                "/planification-salaire", "Vérifier mes revenus"));
                    }
                }
                case "SALARY_PLAN_ADHERENCE" -> {
                    if (value.compareTo(BigDecimal.ONE) > 0) {
                        proposals.add(new Proposal("BUDGET_OVERRUN", factor.code(),
                                value.compareTo(new BigDecimal("1.2000")) >= 0 ? "HIGH" : "MEDIUM",
                                "Rapprocher budget prévu et dépenses réelles",
                                "Les dépenses réelles ont dépassé les budgets utilisables en moyenne. "
                                        + "Repérez les catégories à ajuster, sans modifier vos anciens plans.",
                                "/planification-salaire", "Comparer mon budget"));
                    }
                }
                case "GOAL_PROGRESS" -> {
                    if (value.compareTo(new BigDecimal("100.0000")) < 0) {
                        proposals.add(new Proposal("GOAL_PACE", factor.code(),
                                value.compareTo(new BigDecimal("50.0000")) < 0 ? "MEDIUM" : "LOW",
                                "Revoir le rythme de mes objectifs",
                                "La progression est en retrait par rapport au temps écoulé pour les objectifs utilisables. "
                                        + "Simulez une contribution réaliste avant de modifier votre plan.",
                                "/objectifs", "Simuler mon épargne"));
                    }
                }
                default -> { /* Unavailable and unsupported factors never imply a recommendation. */ }
            }
        }
        return proposals.stream().sorted(Comparator
                .comparingInt((Proposal p) -> priority(p.priority()))
                .thenComparing(Proposal::type)).toList();
    }

    private int priority(String value) {
        return switch (value) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }

    public record Proposal(String type, String factorCode, String priority, String title,
            String message, String actionPath, String actionLabel) { }
}
