package com.azizx25.smartfinancialwellness.backend.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogEntryRepository repository;

    public AuditService(AuditLogEntryRepository repository) {
        this.repository = repository;
    }

    public void record(UUID userId, String action, String resourceType, UUID resourceId) {
        repository.save(new AuditLogEntry(
                userId,
                action,
                resourceType,
                resourceId,
                AuditLogEntry.Outcome.SUCCEEDED,
                Map.of()));
    }
}
