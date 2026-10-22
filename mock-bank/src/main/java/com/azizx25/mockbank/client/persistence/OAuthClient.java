package com.azizx25.mockbank.client.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_client")
public class OAuthClient {

    @Id
    private UUID id;

    @Column(name = "client_identifier", nullable = false, unique = true, length = 80)
    private String clientIdentifier;

    @Column(name = "client_name", nullable = false, length = 160)
    private String clientName;

    @Column(name = "redirect_uri", nullable = false, length = 500)
    private String redirectUri;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected OAuthClient() {
    }

    public UUID getId() {
        return id;
    }

    public String getClientIdentifier() {
        return clientIdentifier;
    }

    public String getClientName() {
        return clientName;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}