package com.azizx25.smartfinancialwellness.user.persistence;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "notification_preference", schema = "smart_financial_wellness")
public class NotificationPreference {

    public enum Channel {
        IN_APP, EMAIL, PUSH
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "minimum_priority", nullable = false, length = 20)
    private Priority minimumPriority;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected NotificationPreference() {
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Priority getMinimumPriority() {
        return minimumPriority;
    }

    public LocalTime getQuietHoursStart() {
        return quietHoursStart;
    }

    public LocalTime getQuietHoursEnd() {
        return quietHoursEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version;
    }
}
