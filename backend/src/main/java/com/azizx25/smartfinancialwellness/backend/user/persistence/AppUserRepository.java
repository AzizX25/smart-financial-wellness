package com.azizx25.smartfinancialwellness.backend.user.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
}
