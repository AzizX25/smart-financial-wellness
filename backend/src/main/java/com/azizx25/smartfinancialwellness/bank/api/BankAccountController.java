package com.azizx25.smartfinancialwellness.bank.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.BankDataService;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankAccountResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankBalanceResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankTransactionPageResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/bank/accounts")
public class BankAccountController {

    private final BankDataService bankDataService;

    public BankAccountController(BankDataService bankDataService) {
        this.bankDataService = bankDataService;
    }

    @GetMapping
    public List<BankAccountResponse> listAccounts(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return bankDataService.listAccounts(principal.userId());
    }

    @GetMapping("/{accountId}")
    public BankAccountResponse getAccount(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID accountId) {
        return bankDataService.getAccount(principal.userId(), accountId);
    }

    @GetMapping("/{accountId}/balance")
    public BankBalanceResponse getBalance(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID accountId) {
        return bankDataService.getBalance(principal.userId(), accountId);
    }

    @GetMapping("/{accountId}/transactions")
    public BankTransactionPageResponse listTransactions(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID accountId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bankDataService.listTransactions(
                principal.userId(),
                accountId,
                from,
                to,
                page,
                size);
    }
}
