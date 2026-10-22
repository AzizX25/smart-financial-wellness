package com.azizx25.mockbank.account;

import java.util.List;

import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.account.persistence.BankAccountRepository;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.CustomerNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountQueryService {

    private final BankCustomerRepository customerRepository;
    private final BankAccountRepository accountRepository;
    private final AccountOwnershipResolver accountOwnershipResolver;

    public AccountQueryService(
            BankCustomerRepository customerRepository,
            BankAccountRepository accountRepository,
            AccountOwnershipResolver accountOwnershipResolver) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.accountOwnershipResolver = accountOwnershipResolver;
    }

    public List<AccountSummary> listAccounts(String externalCustomerId) {
        BankCustomer customer = resolveCustomer(externalCustomerId);
        return accountRepository.findByBankCustomerIdOrderByExternalAccountIdAsc(customer.getId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public AccountSummary loadOwnedAccount(String externalCustomerId, String externalAccountId) {
        return toSummary(accountOwnershipResolver.resolveOwnedAccount(
                externalCustomerId,
                externalAccountId));
    }

    private BankCustomer resolveCustomer(String externalCustomerId) {
        return customerRepository.findByExternalCustomerId(externalCustomerId)
                .orElseThrow(CustomerNotFoundException::new);
    }

    private AccountSummary toSummary(BankAccount account) {
        return new AccountSummary(
                account.getExternalAccountId(),
                account.getDisplayName(),
                account.getAccountType(),
                account.getCurrency(),
                maskAccountReference(account.getExternalAccountId()),
                account.getStatus(),
                account.getCreatedAt());
    }

    public static String maskAccountReference(String externalAccountId) {
        String sanitized = externalAccountId.replaceAll("[^A-Za-z0-9]", "");
        if (sanitized.length() <= 4) {
            return "****" + sanitized;
        }
        return "****" + sanitized.substring(sanitized.length() - 4);
    }
}
