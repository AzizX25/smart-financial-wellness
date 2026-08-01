package com.azizx25.mockbank.account.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.azizx25.mockbank.account.AccountQueryService;
import com.azizx25.mockbank.account.AccountSummary;
import com.azizx25.mockbank.account.BalanceQueryService;
import com.azizx25.mockbank.account.BalanceSummary;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/accounts")
public class AccountController {

    private final AccountQueryService accountQueryService;
    private final BalanceQueryService balanceQueryService;

    public AccountController(
            AccountQueryService accountQueryService,
            BalanceQueryService balanceQueryService) {
        this.accountQueryService = accountQueryService;
        this.balanceQueryService = balanceQueryService;
    }

    @GetMapping
    public List<AccountSummary> listAccounts(
            @PathVariable("customerId") String customerId) {
        return accountQueryService.listAccounts(customerId);
    }

    @GetMapping("/{accountId}")
    public AccountSummary getAccount(
            @PathVariable("customerId") String customerId,
            @PathVariable("accountId") String accountId) {
        return accountQueryService.loadOwnedAccount(customerId, accountId);
    }

    @GetMapping("/{accountId}/balance")
    public BalanceSummary getBalance(
            @PathVariable("customerId") String customerId,
            @PathVariable("accountId") String accountId) {
        return balanceQueryService.loadBalance(customerId, accountId);
    }
}