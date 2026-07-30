package com.azizx25.smartfinancialwellness.auth.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select identity
              from AuthenticationIdentity identity
              join fetch identity.user
             where identity.id = :identityId
            """)
    Optional<AuthenticationIdentity> findByIdForUpdate(@Param("identityId") UUID identityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select identity
              from AuthenticationIdentity identity
              join fetch identity.user
             where identity.identityType = :identityType
               and identity.normalizedIdentifier = :normalizedIdentifier
            """)
    Optional<AuthenticationIdentity> findByEmailForUpdate(
            @Param("identityType") AuthenticationIdentity.Type identityType,
            @Param("normalizedIdentifier") String normalizedIdentifier);
}
