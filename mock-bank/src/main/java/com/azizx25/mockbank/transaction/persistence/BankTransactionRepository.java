package com.azizx25.mockbank.transaction.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByBankAccountIdAndExternalTransactionId(
            UUID bankAccountId,
            String externalTransactionId);

    List<BankTransaction> findByBankAccountIdOrderByBookingDateAscExternalTransactionIdAsc(
            UUID bankAccountId);

    void deleteByBankAccountId(UUID bankAccountId);

    long countByStatus(String status);

    Page<BankTransaction> findAllByOrderByBookingDateDescCreatedAtDesc(Pageable pageable);

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
