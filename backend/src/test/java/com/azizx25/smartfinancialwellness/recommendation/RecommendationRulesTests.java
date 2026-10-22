package com.azizx25.smartfinancialwellness.recommendation;

import java.math.BigDecimal;
import java.util.List;

import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.FactorResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationRulesTests {
    private final RecommendationRules rules = new RecommendationRules();

    @Test
    void selectsOnlySupportedAvailableFactsAndOrdersDeterministically() {
        var facts = List.of(factor("GOAL_PROGRESS", "40"), factor("INCOME_STABILITY", "0.15"),
                factor("SAVINGS_RATE", "-0.02"), factor("SALARY_PLAN_ADHERENCE", "1.2"),
                factor("ESSENTIAL_EXPENSE_BURDEN", "0.9"));
        assertThat(rules.select(facts)).extracting(RecommendationRules.Proposal::type)
                .containsExactly("BUDGET_OVERRUN", "SAVINGS_MARGIN", "GOAL_PACE", "INCOME_VARIATION");
        assertThat(rules.select(List.of(new FactorResponse("SAVINGS_RATE", 30, null,
                "RATIO", null, false, "No history", "NO_HISTORY")))).isEmpty();
    }

    @Test
    void treatsBoundariesExplicitlyWithoutFabricatingHealthyRecommendations() {
        assertThat(rules.select(List.of(factor("SAVINGS_RATE", "0.1"),
                factor("INCOME_STABILITY", "0.1499"), factor("SALARY_PLAN_ADHERENCE", "1"),
                factor("GOAL_PROGRESS", "100")))).isEmpty();
        assertThat(rules.select(List.of(factor("SAVINGS_RATE", "0"))).getFirst().priority()).isEqualTo("HIGH");
        assertThat(rules.select(List.of(factor("SAVINGS_RATE", "0.0999"))).getFirst().priority()).isEqualTo("MEDIUM");
        assertThat(rules.select(List.of(factor("GOAL_PROGRESS", "50"))).getFirst().priority()).isEqualTo("LOW");
    }

    private FactorResponse factor(String code, String value) {
        return new FactorResponse(code, 10, new BigDecimal(value).setScale(4), "RATIO",
                new BigDecimal("40.0000"), true, "Verified fixture", null);
    }
}
