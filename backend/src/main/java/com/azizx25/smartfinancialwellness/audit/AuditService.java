package com.azizx25.smartfinancialwellness.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public void recordSystem(
            String action,
            String resourceType,
            UUID resourceId,
            AuditLogEntry.Outcome outcome) {
        repository.save(systemEntry(action, resourceType, resourceId, outcome));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystemInNewTransaction(
            String action,
            String resourceType,
            UUID resourceId,
            AuditLogEntry.Outcome outcome) {
        repository.save(systemEntry(action, resourceType, resourceId, outcome));
    }

    private AuditLogEntry systemEntry(
            String action,
            String resourceType,
            UUID resourceId,
            AuditLogEntry.Outcome outcome) {
        return new AuditLogEntry(
                AuditLogEntry.ActorType.SYSTEM,
                null,
                action,
                resourceType,
                resourceId,
                outcome,
                Map.of());
    }
}
