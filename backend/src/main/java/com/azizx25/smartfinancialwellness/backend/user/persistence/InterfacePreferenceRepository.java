package com.azizx25.smartfinancialwellness.backend.user.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InterfacePreferenceRepository extends JpaRepository<InterfacePreference, UUID> {
}
