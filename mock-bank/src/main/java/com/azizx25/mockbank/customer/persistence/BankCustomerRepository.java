package com.azizx25.mockbank.customer.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankCustomerRepository extends JpaRepository<BankCustomer, UUID> {

    Optional<BankCustomer> findByExternalCustomerId(String externalCustomerId);
}