package com.azizx25.mockbank.transaction;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.account.persistence.BankAccountRepository;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.AccountNotFoundException;
import com.azizx25.mockbank.exception.AccountNotOwnedException;
import com.azizx25.mockbank.exception.CustomerNotFoundException;
import com.azizx25.mockbank.exception.InvalidTransactionDateRangeException;
import com.azizx25.mockbank.transaction.persistence.BankTransaction;
import com.azizx25.mockbank.transaction.persistence.BankTransactionRepository;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final BankCustomerRepository customerRepository;
    private final BankAccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;

    public TransactionQueryService(
            BankCustomerRepository customerRepository,
            BankAccountRepository accountRepository,
            BankTransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public PaginatedTransactionResult loadTransactions(
            String externalCustomerId,
            String externalAccountId,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidTransactionDateRangeException();
        }

        PageRequest pageRequest = PageRequest.of(page, size);
        BankAccount account = resolveOwnedAccount(externalCustomerId, externalAccountId);
        var transactionPage = findTransactions(account, fromDate, toDate, pageRequest);

        return new PaginatedTransactionResult(
                transactionPage.getContent().stream().map(this::toSummary).toList(),
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages(),
                transactionPage.hasNext(),
                transactionPage.hasPrevious());
    }

    private Page<BankTransaction> findTransactions(
            BankAccount account,
            LocalDate fromDate,
            LocalDate toDate,
            PageRequest pageRequest) {
        if (fromDate != null && toDate != null) {
            return transactionRepository
                    .findByBankAccountIdAndBookingDateBetweenOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), fromDate, toDate, pageRequest);
        }
        if (fromDate != null) {
            return transactionRepository
                    .findByBankAccountIdAndBookingDateGreaterThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), fromDate, pageRequest);
        }
        if (toDate != null) {
            return transactionRepository
                    .findByBankAccountIdAndBookingDateLessThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
                            account.getId(), toDate, pageRequest);
        }
        return transactionRepository.findByBankAccountIdOrderByBookingDateDescExternalTransactionIdDesc(
                account.getId(), pageRequest);
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

    private TransactionSummary toSummary(BankTransaction transaction) {
        return new TransactionSummary(
                transaction.getExternalTransactionId(),
                transaction.getBookingDate(),
                transaction.getValueDate(),
                transaction.getAmount(),
                transaction.getDirection(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getCounterpartyName(),
                transaction.getStatus(),
                transaction.getCreatedAt());
    }
}
