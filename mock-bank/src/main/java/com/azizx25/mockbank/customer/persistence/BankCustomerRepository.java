package com.azizx25.mockbank.customer.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BankCustomerRepository extends JpaRepository<BankCustomer, UUID> {

    Optional<BankCustomer> findByExternalCustomerId(String externalCustomerId);

    Optional<BankCustomer> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByStatus(String status);

    @Query("""
            SELECT customer FROM BankCustomer customer
            WHERE :search IS NULL
               OR LOWER(customer.username) LIKE :search
               OR LOWER(customer.displayName) LIKE :search
            """)
    Page<BankCustomer> search(String search, Pageable pageable);
}
