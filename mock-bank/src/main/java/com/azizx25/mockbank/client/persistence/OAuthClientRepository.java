package com.azizx25.mockbank.client.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthClientRepository extends JpaRepository<OAuthClient, UUID> {

    Optional<OAuthClient> findByClientIdentifier(String clientIdentifier);

    Optional<OAuthClient> findByClientIdentifierAndEnabledTrue(String clientIdentifier);

    List<OAuthClient> findByEnabledTrueOrderByClientIdentifierAsc();
}