package com.azizx25.smartfinancialwellness.bank.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.LocalBankDataService;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.LocalAccountResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.DashboardSummaryResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.LocalTransactionPageResponse;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class LocalBankDataController {

    private final LocalBankDataService localBankDataService;

    public LocalBankDataController(LocalBankDataService localBankDataService) {
        this.localBankDataService = localBankDataService;
    }

    @GetMapping("/accounts")
    public List<LocalAccountResponse> accounts(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return localBankDataService.listAccounts(principal.userId());
    }

    @GetMapping("/transactions")
    public LocalTransactionPageResponse transactions(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionRecord.Direction direction,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(required = false) @Size(max = 160) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return localBankDataService.listTransactions(
                principal.userId(), accountId, from, to, direction,
                categoryId, uncategorized, approved, search, page, size);
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummaryResponse dashboardSummary(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam
            @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month) {
        return localBankDataService.dashboardSummary(principal.userId(), YearMonth.parse(month));
    }
}
