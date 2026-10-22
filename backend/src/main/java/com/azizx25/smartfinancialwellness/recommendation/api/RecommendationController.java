package com.azizx25.smartfinancialwellness.recommendation.api;

import java.util.UUID;

import com.azizx25.smartfinancialwellness.recommendation.RecommendationService;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.GenerationResponse;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.PageResponse;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.RecommendationResponse;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.StatusRequest;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    public RecommendationController(RecommendationService service) { this.service = service; }

    @PostMapping("/current")
    public GenerationResponse generate(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = "[A-Z]{3}") String currency) {
        return service.generate(principal.userId(), currency);
    }

    @GetMapping
    public PageResponse list(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = "[A-Z]{3}") String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.list(principal.userId(), currency, page, size);
    }

    @PatchMapping("/{id}/status")
    public RecommendationResponse feedback(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.feedback(principal.userId(), id, request.status());
    }
}
