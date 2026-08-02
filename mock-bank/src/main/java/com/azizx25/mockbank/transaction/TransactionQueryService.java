package com.azizx25.mockbank.transaction;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.azizx25.mockbank.account.AccountOwnershipResolver;
import com.azizx25.mockbank.account.persistence.BankAccount;
import com.azizx25.mockbank.exception.InvalidTransactionDateRangeException;
import com.azizx25.mockbank.exception.InvalidTransactionPaginationException;
import com.azizx25.mockbank.transaction.persistence.BankTransaction;
import com.azizx25.mockbank.transaction.persistence.BankTransactionRepository;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountOwnershipResolver accountOwnershipResolver;
    private final BankTransactionRepository transactionRepository;

    public TransactionQueryService(
            AccountOwnershipResolver accountOwnershipResolver,
            BankTransactionRepository transactionRepository) {
        this.accountOwnershipResolver = accountOwnershipResolver;
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
        validatePagination(page, size);

        PageRequest pageRequest = PageRequest.of(page, size);
        BankAccount account = accountOwnershipResolver.resolveOwnedAccount(
                externalCustomerId,
                externalAccountId);
        var transactionPage = findTransactions(account, fromDate, toDate, pageRequest);

        return new PaginatedTransactionResult(
                transactionPage.getContent().stream().map(this::toSummary).toList(),
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages(),
                transactionPage.isFirst(),
                transactionPage.isLast());
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new InvalidTransactionPaginationException(
                    "Transaction page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidTransactionPaginationException(
                    "Transaction size must be between 1 and " + MAX_PAGE_SIZE);
        }
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
                transaction.getStatus());
    }
}
