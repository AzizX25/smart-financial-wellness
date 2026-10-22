package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("""
            select category from Category category
            where category.active = true
              and (category.user is null or category.user.id = :userId)
            order by case when category.user is null then 0 else 1 end,
                     category.nature, category.label, category.id
            """)
    List<Category> findVisibleActive(@Param("userId") UUID userId);

    @Query("""
            select category from Category category
            where category.id = :categoryId
              and category.active = true
              and (category.user is null or category.user.id = :userId)
            """)
    Optional<Category> findVisibleActiveById(
            @Param("categoryId") UUID categoryId,
            @Param("userId") UUID userId);

    Optional<Category> findByIdAndUser_Id(UUID categoryId, UUID userId);

    boolean existsByUser_IdAndLabelIgnoreCase(UUID userId, String label);

    boolean existsByUser_IdAndLabelIgnoreCaseAndIdNot(UUID userId, String label, UUID id);

    Optional<Category> findByCodeAndUserIsNull(String code);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM smart_financial_wellness.transaction_record WHERE category_id = :categoryId
                UNION ALL
                SELECT 1 FROM smart_financial_wellness.category_prediction WHERE category_id = :categoryId
                UNION ALL
                SELECT 1 FROM smart_financial_wellness.transaction_category_history
                 WHERE previous_category_id = :categoryId OR new_category_id = :categoryId
                UNION ALL
                SELECT 1 FROM smart_financial_wellness.categorization_review_candidate
                 WHERE category_id = :categoryId
                UNION ALL
                SELECT 1 FROM smart_financial_wellness.user_merchant_memory
                 WHERE category_id = :categoryId
            )
            """, nativeQuery = true)
    boolean isReferenced(@Param("categoryId") UUID categoryId);

    @Query(value = """
            SELECT transaction.currency AS currency,
                   category.id AS categoryId,
                   category.label AS categoryLabel,
                   category.nature AS categoryNature,
                   COUNT(*) AS transactionCount,
                   SUM(transaction.amount) AS totalAmount
              FROM smart_financial_wellness.transaction_record transaction
              JOIN smart_financial_wellness.category category ON category.id = transaction.category_id
             WHERE transaction.user_id = :userId
               AND transaction.deleted_at IS NULL
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
             GROUP BY transaction.currency, category.id, category.label, category.nature
             ORDER BY transaction.currency, category.nature, category.label, category.id
            """, nativeQuery = true)
    List<CategorySummaryRow> summarize(
            @Param("userId") UUID userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    interface CategorySummaryRow {
        String getCurrency();
        UUID getCategoryId();
        String getCategoryLabel();
        String getCategoryNature();
        long getTransactionCount();
        BigDecimal getTotalAmount();
    }
}
