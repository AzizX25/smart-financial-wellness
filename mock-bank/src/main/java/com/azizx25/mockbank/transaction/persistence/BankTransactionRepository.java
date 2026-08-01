package com.azizx25.mockbank.transaction.persistence;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Page<BankTransaction> findByBankAccountIdOrderByBookingDateDescExternalTransactionIdDesc(
            UUID bankAccountId,
            Pageable pageable);

    Page<BankTransaction> findByBankAccountIdAndBookingDateGreaterThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
            UUID bankAccountId,
            LocalDate fromDate,
            Pageable pageable);

    Page<BankTransaction> findByBankAccountIdAndBookingDateLessThanEqualOrderByBookingDateDescExternalTransactionIdDesc(
            UUID bankAccountId,
            LocalDate toDate,
            Pageable pageable);

    Page<BankTransaction> findByBankAccountIdAndBookingDateBetweenOrderByBookingDateDescExternalTransactionIdDesc(
            UUID bankAccountId,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable);
}
