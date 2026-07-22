package com.azizx25.smartfinancialwellness.backend.auth.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {
}
