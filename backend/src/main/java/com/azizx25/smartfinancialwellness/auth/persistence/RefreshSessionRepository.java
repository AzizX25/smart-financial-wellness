package com.azizx25.smartfinancialwellness.auth.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"authenticationIdentity", "authenticationIdentity.user"})
    Optional<RefreshSession> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshSession session
               set session.reuseDetectedAt = :detectedAt
             where session.id = :sessionId
            """)
    int markReuseDetected(@Param("sessionId") UUID sessionId, @Param("detectedAt") Instant detectedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshSession session
               set session.revokedAt = coalesce(session.revokedAt, :revokedAt),
                   session.revocationReason = :reason
             where session.familyId = :familyId
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update RefreshSession session
               set session.revokedAt = :revokedAt,
                   session.revocationReason = :reason
             where session.authenticationIdentity.id = :identityId
               and session.revokedAt is null
            """)
    int revokeAllForIdentity(
            @Param("identityId") UUID identityId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason);
}
