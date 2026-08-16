package com.azizx25.smartfinancialwellness.goal.api;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.goal.GoalAllocationService;
import com.azizx25.smartfinancialwellness.goal.GoalService;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationOverviewResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationReplaceRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalContributionRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalContributionResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalStatusRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalSimulationRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalSimulationResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalWriteRequest;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private static final String CURRENCY_PATTERN = "[A-Z]{3}";
    private static final String MONTH_PATTERN = "[0-9]{4}-(0[1-9]|1[0-2])";

    private final GoalService goalService;
    private final GoalAllocationService allocationService;

    public GoalController(GoalService goalService, GoalAllocationService allocationService) {
        this.goalService = goalService;
        this.allocationService = allocationService;
    }

    @GetMapping
    public List<GoalResponse> list(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return goalService.list(principal.userId(), currency, includeArchived);
    }

    @PostMapping
    public ResponseEntity<GoalResponse> create(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody GoalWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(principal.userId(), request));
    }

    @GetMapping("/allocations/{month}")
    public GoalAllocationOverviewResponse allocationOverview(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable @Pattern(regexp = MONTH_PATTERN) String month,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency) {
        return allocationService.overview(principal.userId(), YearMonth.parse(month), currency);
    }

    @PutMapping("/allocations/{month}")
    public GoalAllocationOverviewResponse replaceAllocations(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable @Pattern(regexp = MONTH_PATTERN) String month,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency,
            @Valid @RequestBody GoalAllocationReplaceRequest request) {
        return allocationService.replace(principal.userId(), YearMonth.parse(month), currency, request);
    }

    @GetMapping("/{goalId}")
    public GoalResponse get(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId) {
        return goalService.get(principal.userId(), goalId);
    }

    @PutMapping("/{goalId}")
    public GoalResponse replace(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId,
            @Valid @RequestBody GoalWriteRequest request) {
        return goalService.replace(principal.userId(), goalId, request);
    }

    @PatchMapping("/{goalId}/status")
    public GoalResponse changeStatus(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId,
            @Valid @RequestBody GoalStatusRequest request) {
        return goalService.changeStatus(principal.userId(), goalId, request.status());
    }

    @DeleteMapping("/{goalId}")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId) {
        goalService.archive(principal.userId(), goalId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{goalId}/contributions")
    public List<GoalContributionResponse> contributions(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId) {
        return goalService.contributions(principal.userId(), goalId);
    }

    @PostMapping("/{goalId}/contributions")
    public ResponseEntity<GoalResponse> addContribution(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId,
            @Valid @RequestBody GoalContributionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(goalService.addContribution(principal.userId(), goalId, request));
    }

    @DeleteMapping("/{goalId}/contributions/{contributionId}")
    public GoalResponse deleteContribution(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId,
            @PathVariable UUID contributionId) {
        return goalService.deleteContribution(principal.userId(), goalId, contributionId);
    }

    @PostMapping("/{goalId}/simulations")
    public GoalSimulationResponse simulate(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID goalId,
            @Valid @RequestBody GoalSimulationRequest request) {
        return allocationService.simulate(principal.userId(), goalId, request);
    }
}
