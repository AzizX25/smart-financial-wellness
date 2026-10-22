package com.azizx25.smartfinancialwellness.salary.api;

import java.time.YearMonth;

import com.azizx25.smartfinancialwellness.salary.SalaryPlanService;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanPageResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanProposalResponse;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanPutRequest;
import com.azizx25.smartfinancialwellness.salary.api.SalaryPlanDtos.SalaryPlanResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/salary-plans")
public class SalaryPlanController {

    private static final String MONTH_PATTERN = "[0-9]{4}-(0[1-9]|1[0-2])";
    private static final String CURRENCY_PATTERN = "[A-Z]{3}";

    private final SalaryPlanService salaryPlanService;

    public SalaryPlanController(SalaryPlanService salaryPlanService) {
        this.salaryPlanService = salaryPlanService;
    }

    @GetMapping("/{month}")
    public SalaryPlanResponse get(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable @Pattern(regexp = MONTH_PATTERN) String month,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency) {
        return salaryPlanService.get(principal.userId(), YearMonth.parse(month), currency);
    }

    @PutMapping("/{month}")
    public SalaryPlanResponse replace(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable @Pattern(regexp = MONTH_PATTERN) String month,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency,
            @Valid @RequestBody SalaryPlanPutRequest request) {
        return salaryPlanService.replace(principal.userId(), YearMonth.parse(month), currency, request);
    }

    @GetMapping
    public SalaryPlanPageResponse list(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
        return salaryPlanService.list(principal.userId(), currency, page, size);
    }

    @GetMapping("/proposals")
    public SalaryPlanProposalResponse proposals(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = MONTH_PATTERN) String month,
            @RequestParam @Pattern(regexp = CURRENCY_PATTERN) String currency) {
        return salaryPlanService.proposals(principal.userId(), YearMonth.parse(month), currency);
    }
}
