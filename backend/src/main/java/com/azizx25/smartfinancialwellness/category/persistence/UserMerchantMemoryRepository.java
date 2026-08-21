package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserMerchantMemoryRepository extends JpaRepository<UserMerchantMemory, UUID> {

    @Query("""
            select memory from UserMerchantMemory memory
            join fetch memory.category
            where memory.user.id = :userId
            order by memory.updatedAt desc, memory.id
            """)
    List<UserMerchantMemory> findOwned(@Param("userId") UUID userId);

    @Query("""
            select memory from UserMerchantMemory memory
            join fetch memory.category category
            where memory.user.id = :userId
              and memory.normalizedMerchantKey = :merchantKey
              and memory.direction = :direction
              and memory.currency = :currency
              and category.active = true
              and (category.user is null or category.user.id = :userId)
            """)
    Optional<UserMerchantMemory> findActiveMatch(
            @Param("userId") UUID userId,
            @Param("merchantKey") String merchantKey,
            @Param("direction") TransactionRecord.Direction direction,
            @Param("currency") String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserMerchantMemory> findByUser_IdAndNormalizedMerchantKeyAndDirectionAndCurrency(
            UUID userId,
            String normalizedMerchantKey,
            TransactionRecord.Direction direction,
            String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserMerchantMemory> findByIdAndUser_Id(UUID id, UUID userId);
}
