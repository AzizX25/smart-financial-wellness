package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
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
@Table(name = "category", schema = "smart_financial_wellness")
public class Category {

    public enum Nature { EXPENSE, INCOME, TRANSFER, NEUTRAL }
    public enum Direction { DEBIT, CREDIT, BOTH }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(name = "taxonomy_version", nullable = false, length = 40)
    private String taxonomyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Nature nature;

    @Column(name = "icon_key", length = 40)
    private String iconKey;

    @Column(length = 7)
    private String color;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected Category() {
    }

    public Category(AppUser owner, String name, Nature categoryNature, String icon, String categoryColor, Instant now) {
        id = UUID.randomUUID();
        user = owner;
        code = "PERSONAL_" + id.toString().replace("-", "").toUpperCase();
        label = name;
        taxonomyVersion = "user-v1";
        nature = categoryNature;
        direction = directionFor(categoryNature);
        iconKey = icon;
        color = categoryColor;
        active = true;
        createdAt = now;
        updatedAt = now;
    }

    public void update(String name, Nature categoryNature, String icon, String categoryColor) {
        label = name;
        nature = categoryNature;
        direction = directionFor(categoryNature);
        iconKey = icon;
        color = categoryColor;
    }

    private Direction directionFor(Nature value) {
        return switch (value) {
            case EXPENSE -> Direction.DEBIT;
            case INCOME -> Direction.CREDIT;
            case TRANSFER, NEUTRAL -> Direction.BOTH;
        };
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user == null ? null : user.getId(); }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public Direction getDirection() { return direction; }
    public Nature getNature() { return nature; }
    public String getIconKey() { return iconKey; }
    public String getColor() { return color; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
