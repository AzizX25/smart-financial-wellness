package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryPredictionRepository extends JpaRepository<CategoryPrediction, UUID> {
}
