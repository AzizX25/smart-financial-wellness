package com.azizx25.smartfinancialwellness.auth.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthenticationIdentityRepository extends JpaRepository<AuthenticationIdentity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<AuthenticationIdentity> findByIdentityTypeAndNormalizedIdentifier(
            AuthenticationIdentity.Type identityType,
            String normalizedIdentifier);

    @EntityGraph(attributePaths = "user")
    Optional<AuthenticationIdentity> findByUserId(UUID userId);

    boolean existsByIdentityTypeAndNormalizedIdentifier(
            AuthenticationIdentity.Type identityType,
            String normalizedIdentifier);
}
