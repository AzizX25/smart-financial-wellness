package com.azizx25.smartfinancialwellness.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log_entry", schema = "smart_financial_wellness")
public class AuditLogEntry {

    public enum ActorType {
        USER, SYSTEM, ADMIN, SERVICE
    }

    public enum Outcome {
        SUCCEEDED, FAILED, DENIED
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", columnDefinition = "jsonb")
    private Map<String, Object> changeSummary;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            Outcome outcome,
            Map<String, Object> changeSummary) {
        this.id = UUID.randomUUID();
        this.actorType = ActorType.USER;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.changeSummary = changeSummary;
        this.correlationId = UUID.randomUUID();
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }
}
