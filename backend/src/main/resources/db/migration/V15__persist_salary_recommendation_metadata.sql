ALTER TABLE monthly_plan
    ADD COLUMN recommendation_metadata_available BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recommendation_period_start DATE,
    ADD COLUMN recommendation_period_end DATE,
    ADD COLUMN recommendation_usable_month_count INTEGER,
    ADD COLUMN recommendation_fallback_reason VARCHAR(60),
    ADD CONSTRAINT ck_monthly_plan_recommendation_month_count CHECK (
        recommendation_usable_month_count IS NULL
        OR recommendation_usable_month_count BETWEEN 0 AND 3
    ),
    ADD CONSTRAINT ck_monthly_plan_recommendation_period CHECK (
        (recommendation_period_start IS NULL AND recommendation_period_end IS NULL)
        OR (
            recommendation_period_start IS NOT NULL
            AND recommendation_period_end IS NOT NULL
            AND recommendation_period_start <= recommendation_period_end
        )
    ),
    ADD CONSTRAINT ck_monthly_plan_recommendation_metadata CHECK (
        (
            recommendation_metadata_available = FALSE
            AND recommendation_period_start IS NULL
            AND recommendation_period_end IS NULL
            AND recommendation_usable_month_count IS NULL
            AND recommendation_fallback_reason IS NULL
        )
        OR (
            recommendation_metadata_available = TRUE
            AND BTRIM(calculation_method) <> ''
            AND BTRIM(method_version) <> ''
            AND recommendation_period_start IS NOT NULL
            AND recommendation_period_end IS NOT NULL
            AND recommendation_usable_month_count IS NOT NULL
            AND recommendation_usable_month_count BETWEEN 0 AND 3
            AND (
                (
                    recommendation_usable_month_count = 0
                    AND recommendation_fallback_reason IS NOT NULL
                    AND BTRIM(recommendation_fallback_reason) <> ''
                )
                OR (
                    recommendation_usable_month_count > 0
                    AND recommendation_fallback_reason IS NULL
                )
            )
        )
    );
