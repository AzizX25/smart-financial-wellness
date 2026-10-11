package com.azizx25.smartfinancialwellness.score.api;

import java.util.UUID;

import com.azizx25.smartfinancialwellness.score.FinancialScoreService;
import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.ScorePageResponse;
import com.azizx25.smartfinancialwellness.score.api.FinancialScoreDtos.ScoreResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/financial-scores")
public class FinancialScoreController {

    private static final String CURRENCY = "[A-Z]{3}";
    private final FinancialScoreService service;

    public FinancialScoreController(FinancialScoreService service) {
        this.service = service;
    }

    @PostMapping("/current")
    public ScoreResponse calculateCurrent(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY) String currency) {
        return service.calculateCurrent(principal.userId(), currency);
    }

    @GetMapping
    public ScorePageResponse list(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY) String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.list(principal.userId(), currency, page, size);
    }

    @GetMapping("/{scoreId}")
    public ScoreResponse get(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID scoreId) {
        return service.get(principal.userId(), scoreId);
    }
}
