package com.azizx25.mockbank.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.azizx25.mockbank.account.persistence.BankAccount;

@Service
@Transactional(readOnly = true)
public class BalanceQueryService {

    private final AccountOwnershipResolver accountOwnershipResolver;

    public BalanceQueryService(AccountOwnershipResolver accountOwnershipResolver) {
        this.accountOwnershipResolver = accountOwnershipResolver;
    }

    public BalanceSummary loadBalance(String externalCustomerId, String externalAccountId) {
        BankAccount account = accountOwnershipResolver.resolveOwnedAccount(
                externalCustomerId,
                externalAccountId);
        return new BalanceSummary(
                account.getExternalAccountId(),
                account.getCurrency(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                account.getStatus(),
                AccountQueryService.maskAccountReference(account.getExternalAccountId()));
    }
}
