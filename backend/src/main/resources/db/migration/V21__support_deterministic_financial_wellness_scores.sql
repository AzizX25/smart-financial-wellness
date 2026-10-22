ALTER TABLE wellness_evaluation
    ADD COLUMN as_of_date DATE,
    ADD COLUMN time_zone VARCHAR(80),
    ADD COLUMN available_base_weight SMALLINT,
    ADD COLUMN input_fingerprint CHAR(64);

ALTER TABLE wellness_evaluation
    ALTER COLUMN global_score TYPE NUMERIC(7, 4),
    DROP CONSTRAINT ck_wellness_evaluation_level,
    ADD CONSTRAINT ck_wellness_evaluation_level CHECK (
        level IS NULL OR level IN (
            'CRITICAL', 'FRAGILE', 'STABLE', 'GOOD', 'EXCELLENT',
            'A_RENFORCER', 'EN_PROGRESSION', 'SOLIDE'
        )
    ),
    ADD CONSTRAINT ck_wellness_evaluation_score_metadata CHECK (
        method_version <> 'financial-wellness-score-v1'
        OR (
            as_of_date IS NOT NULL
            AND time_zone IS NOT NULL AND BTRIM(time_zone) <> ''
            AND available_base_weight BETWEEN 50 AND 100
            AND input_fingerprint ~ '^[0-9a-f]{64}$'
            AND period_end < as_of_date
            AND coverage_rate = available_base_weight / 100.0000
        )
    );

CREATE UNIQUE INDEX uk_wellness_evaluation_v1_idempotency
    ON wellness_evaluation(user_id, currency, as_of_date, method_version, input_fingerprint)
    WHERE method_version = 'financial-wellness-score-v1';

ALTER TABLE evaluation_dimension
    ALTER COLUMN score TYPE NUMERIC(7, 4),
    ALTER COLUMN score DROP NOT NULL,
    ADD COLUMN raw_value NUMERIC(19, 4),
    ADD COLUMN unit VARCHAR(30),
    ADD COLUMN available BOOLEAN,
    ADD COLUMN explanation VARCHAR(1000),
    ADD COLUMN unavailable_reason VARCHAR(500),
    DROP CONSTRAINT ck_evaluation_dimension_type,
    DROP CONSTRAINT ck_evaluation_dimension_score,
    ADD CONSTRAINT ck_evaluation_dimension_type CHECK (
        dimension_type IN (
            'MONTHLY_BALANCE', 'BUDGET_ADHERENCE', 'FINANCIAL_SAFETY',
            'SAVINGS_AND_GOALS', 'SHORT_TERM_OUTLOOK',
            'SAVINGS_RATE', 'ESSENTIAL_EXPENSE_BURDEN', 'INCOME_STABILITY',
            'BALANCE_RESILIENCE', 'SALARY_PLAN_ADHERENCE', 'GOAL_PROGRESS'
        )
    ),
    ADD CONSTRAINT ck_evaluation_dimension_score CHECK (
        score IS NULL OR score BETWEEN 0 AND 100
    ),
    ADD CONSTRAINT ck_evaluation_factor_contract CHECK (
        dimension_type NOT IN (
            'SAVINGS_RATE', 'ESSENTIAL_EXPENSE_BURDEN', 'INCOME_STABILITY',
            'BALANCE_RESILIENCE', 'SALARY_PLAN_ADHERENCE', 'GOAL_PROGRESS'
        )
        OR (
            explanation IS NOT NULL AND BTRIM(explanation) <> ''
            AND unit IS NOT NULL AND BTRIM(unit) <> ''
            AND (
                available = TRUE AND raw_value IS NOT NULL AND score IS NOT NULL
                AND unavailable_reason IS NULL AND coverage_rate = 1
                OR
                available = FALSE AND raw_value IS NULL AND score IS NULL
                AND unavailable_reason IS NOT NULL AND BTRIM(unavailable_reason) <> ''
                AND coverage_rate = 0
            )
            AND weight = CASE dimension_type
                WHEN 'SAVINGS_RATE' THEN 0.3000
                WHEN 'ESSENTIAL_EXPENSE_BURDEN' THEN 0.2000
                WHEN 'INCOME_STABILITY' THEN 0.1500
                WHEN 'BALANCE_RESILIENCE' THEN 0.1500
                WHEN 'SALARY_PLAN_ADHERENCE' THEN 0.1000
                WHEN 'GOAL_PROGRESS' THEN 0.1000
            END
        )
    );

CREATE OR REPLACE FUNCTION guard_evaluation_root_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF OLD.status NOT IN ('PRODUCED', 'OBSOLETE') THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A produced wellness evaluation is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'PRODUCED'
       AND NEW.status = 'OBSOLETE'
       AND ROW(NEW.id, NEW.user_id, NEW.monthly_plan_id, NEW.forecast_run_id,
           NEW.period_start, NEW.period_end, NEW.currency, NEW.global_score, NEW.level,
           NEW.coverage_rate, NEW.method_version, NEW.produced_at, NEW.created_at,
           NEW.as_of_date, NEW.time_zone, NEW.available_base_weight, NEW.input_fingerprint)
           IS NOT DISTINCT FROM
           ROW(OLD.id, OLD.user_id, OLD.monthly_plan_id, OLD.forecast_run_id,
           OLD.period_start, OLD.period_end, OLD.currency, OLD.global_score, OLD.level,
           OLD.coverage_rate, OLD.method_version, OLD.produced_at, OLD.created_at,
           OLD.as_of_date, OLD.time_zone, OLD.available_base_weight, OLD.input_fingerprint) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'A produced evaluation is immutable; only PRODUCED to OBSOLETE is allowed'
        USING ERRCODE = '23514';
END;
$$;

CREATE OR REPLACE FUNCTION validate_evaluation_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    dimension_count INTEGER;
    dimension_without_indicator_count INTEGER;
    invalid_factor_count INTEGER;
    total_weight NUMERIC;
    calculated_score NUMERIC;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status IN ('PRODUCED', 'OBSOLETE') THEN
        RAISE EXCEPTION 'Create an evaluation as DRAFT before publishing it'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'PRODUCED' AND OLD.status <> 'PRODUCED' THEN
        IF NEW.method_version = 'financial-wellness-score-v1' THEN
            SELECT COUNT(*),
                   COUNT(*) FILTER (WHERE available IS NULL),
                   COALESCE(SUM(weight) FILTER (WHERE available), 0),
                   SUM(score * weight) FILTER (WHERE available)
            INTO dimension_count, invalid_factor_count, total_weight, calculated_score
            FROM evaluation_dimension
            WHERE wellness_evaluation_id = NEW.id;

            IF dimension_count <> 6 OR invalid_factor_count > 0 THEN
                RAISE EXCEPTION 'A V1 score requires all six factor contracts'
                    USING ERRCODE = '23514';
            END IF;

            IF ABS(total_weight * 100 - NEW.available_base_weight) > 0.0001
               OR ABS(calculated_score / total_weight - NEW.global_score) > 0.0001 THEN
                RAISE EXCEPTION 'V1 score weight or total is inconsistent with its factors'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            SELECT COUNT(*), COALESCE(SUM(weight), 0)
            INTO dimension_count, total_weight
            FROM evaluation_dimension
            WHERE wellness_evaluation_id = NEW.id;

            SELECT COUNT(*) INTO dimension_without_indicator_count
            FROM evaluation_dimension dimension
            WHERE dimension.wellness_evaluation_id = NEW.id
              AND NOT EXISTS (
                  SELECT 1 FROM evaluation_indicator indicator
                  WHERE indicator.evaluation_dimension_id = dimension.id
              );

            IF dimension_count = 0 OR dimension_without_indicator_count > 0 THEN
                RAISE EXCEPTION 'A produced evaluation requires dimensions and indicators'
                    USING ERRCODE = '23514';
            END IF;

            IF ABS(total_weight - 1) > 0.0001 THEN
                RAISE EXCEPTION 'Evaluation dimension weights must sum to one'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;
