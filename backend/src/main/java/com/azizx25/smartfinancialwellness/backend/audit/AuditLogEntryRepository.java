package com.azizx25.smartfinancialwellness.backend.audit;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {
}
