package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionCategoryHistoryRepository
        extends JpaRepository<TransactionCategoryHistory, UUID> {
}
