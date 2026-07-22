package com.azizx25.smartfinancialwellness.backend.user.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "interface_preference", schema = "smart_financial_wellness")
public class InterfacePreference {

    public enum Theme {
        LIGHT, DARK, SYSTEM
    }

    public enum TextSize {
        NORMAL, LARGE, EXTRA_LARGE
    }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "language_tag", nullable = false, length = 35)
    private String languageTag;

    @Column(name = "time_zone", nullable = false, length = 80)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Theme theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_size", nullable = false, length = 20)
    private TextSize textSize;

    @Column(name = "high_contrast", nullable = false)
    private boolean highContrast;

    @Column(name = "reduced_motion", nullable = false)
    private boolean reducedMotion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected InterfacePreference() {
    }

    public InterfacePreference(AppUser user) {
        Instant now = Instant.now();
        this.user = user;
        this.languageTag = "fr-TN";
        this.timeZone = "Africa/Tunis";
        this.theme = Theme.SYSTEM;
        this.textSize = TextSize.NORMAL;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLanguageTag() {
        return languageTag;
    }

    public void setLanguageTag(String languageTag) {
        this.languageTag = languageTag;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
    }

    public TextSize getTextSize() {
        return textSize;
    }

    public void setTextSize(TextSize textSize) {
        this.textSize = textSize;
    }

    public boolean isHighContrast() {
        return highContrast;
    }

    public void setHighContrast(boolean highContrast) {
        this.highContrast = highContrast;
    }

    public boolean isReducedMotion() {
        return reducedMotion;
    }

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
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
