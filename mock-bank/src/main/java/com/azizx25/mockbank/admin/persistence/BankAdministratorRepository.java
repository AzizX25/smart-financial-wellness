package com.azizx25.mockbank.admin.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAdministratorRepository extends JpaRepository<BankAdministrator, UUID> {

    Optional<BankAdministrator> findByUsername(String username);
}
