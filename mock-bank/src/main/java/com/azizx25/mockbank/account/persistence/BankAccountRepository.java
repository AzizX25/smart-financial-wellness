package com.azizx25.mockbank.account.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByBankCustomerIdOrderByExternalAccountIdAsc(UUID bankCustomerId);

    Optional<BankAccount> findByBankCustomerIdAndExternalAccountId(UUID bankCustomerId, String externalAccountId);

    Optional<BankAccount> findByExternalAccountId(String externalAccountId);
}