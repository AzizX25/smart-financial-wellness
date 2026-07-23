package com.azizx25.smartfinancialwellness.auth.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {
}
