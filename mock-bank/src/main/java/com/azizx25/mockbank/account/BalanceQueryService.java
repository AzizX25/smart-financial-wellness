package com.azizx25.mockbank.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.account.persistence.BankAccountRepository;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.AccountNotFoundException;
import com.azizx25.mockbank.exception.AccountNotOwnedException;
import com.azizx25.mockbank.exception.CustomerNotFoundException;

@Service
@Transactional(readOnly = true)
public class BalanceQueryService {

    private final BankCustomerRepository customerRepository;
    private final BankAccountRepository accountRepository;

    public BalanceQueryService(
            BankCustomerRepository customerRepository,
            BankAccountRepository accountRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    public BalanceSummary loadBalance(String externalCustomerId, String externalAccountId) {
        BankAccount account = resolveOwnedAccount(externalCustomerId, externalAccountId);
        return new BalanceSummary(
                account.getExternalAccountId(),
                account.getCurrency(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                account.getStatus(),
                AccountQueryService.maskAccountReference(account.getExternalAccountId()));
    }

    BankAccount resolveOwnedAccount(String externalCustomerId, String externalAccountId) {
        BankCustomer customer = customerRepository.findByExternalCustomerId(externalCustomerId)
                .orElseThrow(CustomerNotFoundException::new);
        BankAccount account = accountRepository.findByExternalAccountId(externalAccountId)
                .orElseThrow(AccountNotFoundException::new);
        if (!account.getBankCustomerId().equals(customer.getId())) {
            throw new AccountNotOwnedException();
        }
        return account;
    }
}