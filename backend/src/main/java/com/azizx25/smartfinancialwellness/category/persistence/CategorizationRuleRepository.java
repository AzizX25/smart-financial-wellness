package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, UUID> {
    List<CategorizationRule> findByActiveTrue();
}
