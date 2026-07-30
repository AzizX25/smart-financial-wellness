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

public interface AccountActionTokenRepository extends JpaRepository<AccountActionToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"authenticationIdentity", "authenticationIdentity.user"})
    Optional<AccountActionToken> findByTokenHash(String tokenHash);

    @Query("""
            select max(token.createdAt)
              from AccountActionToken token
             where token.authenticationIdentity.id = :identityId
               and token.actionType = :actionType
            """)
    Optional<Instant> findLatestCreatedAt(
            @Param("identityId") UUID identityId,
            @Param("actionType") AccountActionToken.ActionType actionType);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update AccountActionToken token
               set token.invalidatedAt = :invalidatedAt
             where token.authenticationIdentity.id = :identityId
               and token.actionType = :actionType
               and token.usedAt is null
               and token.invalidatedAt is null
            """)
    int invalidateUnused(
            @Param("identityId") UUID identityId,
            @Param("actionType") AccountActionToken.ActionType actionType,
            @Param("invalidatedAt") Instant invalidatedAt);
}
