CREATE TABLE forecast_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    monthly_plan_id UUID REFERENCES monthly_plan(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    forecast_start_date DATE NOT NULL,
    forecast_end_date DATE NOT NULL,
    forecast_horizon_days INTEGER NOT NULL,
    currency CHAR(3) NOT NULL,
    estimated_final_balance NUMERIC(19, 4),
    final_balance_lower_bound NUMERIC(19, 4),
    final_balance_upper_bound NUMERIC(19, 4),
    deficit_probability NUMERIC(5, 4),
    model_name VARCHAR(100) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    pipeline_version VARCHAR(100) NOT NULL,
    data_cutoff_at TIMESTAMPTZ NOT NULL,
    model_trained_at TIMESTAMPTZ,
    coverage_rate NUMERIC(5, 4),
    mae NUMERIC(19, 4),
    rmse NUMERIC(19, 4),
    additional_metrics JSONB NOT NULL DEFAULT '{}'::JSONB,
    safe_error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_forecast_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_forecast_run_dates CHECK (
        forecast_end_date >= forecast_start_date
        AND forecast_horizon_days = (forecast_end_date - forecast_start_date) + 1
    ),
    CONSTRAINT ck_forecast_run_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_forecast_run_model CHECK (
        BTRIM(model_name) <> '' AND BTRIM(model_version) <> '' AND BTRIM(pipeline_version) <> ''
    ),
    CONSTRAINT ck_forecast_run_interval CHECK (
        final_balance_lower_bound IS NULL OR estimated_final_balance IS NULL
        OR final_balance_upper_bound IS NULL
        OR (final_balance_lower_bound <= estimated_final_balance
            AND estimated_final_balance <= final_balance_upper_bound)
    ),
    CONSTRAINT ck_forecast_run_probability CHECK (
        deficit_probability IS NULL OR deficit_probability BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_forecast_run_coverage CHECK (coverage_rate IS NULL OR coverage_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_forecast_run_metrics CHECK ((mae IS NULL OR mae >= 0) AND (rmse IS NULL OR rmse >= 0)),
    CONSTRAINT ck_forecast_run_execution_dates CHECK (
        (started_at IS NULL OR started_at >= created_at) AND
        (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at))
    ),
    CONSTRAINT ck_forecast_run_running_started CHECK (
        status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') OR started_at IS NOT NULL
    ),
    CONSTRAINT ck_forecast_run_terminal_completed CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR completed_at IS NOT NULL
    )
);

CREATE TABLE forecast_run_account (
    forecast_run_id UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    financial_account_id UUID NOT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    reference_balance NUMERIC(19, 4) NOT NULL,
    reference_balance_at TIMESTAMPTZ NOT NULL,
    estimated_balance NUMERIC(19, 4),
    lower_bound NUMERIC(19, 4),
    upper_bound NUMERIC(19, 4),
    deficit_probability NUMERIC(5, 4),
    first_deficit_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (forecast_run_id, financial_account_id),
    CONSTRAINT ck_forecast_run_account_interval CHECK (
        lower_bound IS NULL OR estimated_balance IS NULL OR upper_bound IS NULL
        OR (lower_bound <= estimated_balance AND estimated_balance <= upper_bound)
    ),
    CONSTRAINT ck_forecast_run_account_probability CHECK (
        deficit_probability IS NULL OR deficit_probability BETWEEN 0 AND 1
    )
);

CREATE TABLE wellness_evaluation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    monthly_plan_id UUID REFERENCES monthly_plan(id) ON DELETE RESTRICT,
    forecast_run_id UUID REFERENCES forecast_run(id) ON DELETE RESTRICT,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    global_score NUMERIC(5, 2),
    level VARCHAR(20),
    coverage_rate NUMERIC(5, 4) NOT NULL DEFAULT 0,
    method_version VARCHAR(100) NOT NULL,
    produced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_wellness_evaluation_dates CHECK (period_end >= period_start),
    CONSTRAINT ck_wellness_evaluation_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_wellness_evaluation_status CHECK (
        status IN ('DRAFT', 'PRODUCED', 'OBSOLETE', 'FAILED')
    ),
    CONSTRAINT ck_wellness_evaluation_score CHECK (
        global_score IS NULL OR global_score BETWEEN 0 AND 100
    ),
    CONSTRAINT ck_wellness_evaluation_level CHECK (
        level IS NULL OR level IN ('CRITICAL', 'FRAGILE', 'STABLE', 'GOOD', 'EXCELLENT')
    ),
    CONSTRAINT ck_wellness_evaluation_coverage CHECK (coverage_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_wellness_evaluation_produced CHECK (
        status NOT IN ('PRODUCED', 'OBSOLETE')
        OR (global_score IS NOT NULL AND level IS NOT NULL AND produced_at IS NOT NULL)
    ),
    CONSTRAINT ck_wellness_evaluation_method CHECK (BTRIM(method_version) <> '')
);

CREATE TABLE wellness_evaluation_account (
    wellness_evaluation_id UUID NOT NULL REFERENCES wellness_evaluation(id) ON DELETE CASCADE,
    financial_account_id UUID NOT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    PRIMARY KEY (wellness_evaluation_id, financial_account_id)
);

CREATE TABLE evaluation_dimension (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wellness_evaluation_id UUID NOT NULL REFERENCES wellness_evaluation(id) ON DELETE CASCADE,
    dimension_type VARCHAR(40) NOT NULL,
    score NUMERIC(5, 2) NOT NULL,
    weight NUMERIC(5, 4) NOT NULL,
    coverage_rate NUMERIC(5, 4) NOT NULL,
    CONSTRAINT uk_evaluation_dimension UNIQUE (wellness_evaluation_id, dimension_type),
    CONSTRAINT ck_evaluation_dimension_type CHECK (
        dimension_type IN ('MONTHLY_BALANCE', 'BUDGET_ADHERENCE', 'FINANCIAL_SAFETY',
            'SAVINGS_AND_GOALS', 'SHORT_TERM_OUTLOOK')
    ),
    CONSTRAINT ck_evaluation_dimension_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_evaluation_dimension_weight CHECK (weight BETWEEN 0 AND 1),
    CONSTRAINT ck_evaluation_dimension_coverage CHECK (coverage_rate BETWEEN 0 AND 1)
);

CREATE TABLE evaluation_indicator (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_dimension_id UUID NOT NULL REFERENCES evaluation_dimension(id) ON DELETE CASCADE,
    indicator_code VARCHAR(80) NOT NULL,
    numeric_value NUMERIC(19, 6),
    unit VARCHAR(30),
    level VARCHAR(20),
    explanation VARCHAR(1000) NOT NULL,
    data_source VARCHAR(100) NOT NULL,
    calculable BOOLEAN NOT NULL,
    unavailable_reason VARCHAR(500),
    CONSTRAINT uk_evaluation_indicator UNIQUE (evaluation_dimension_id, indicator_code),
    CONSTRAINT ck_evaluation_indicator_code CHECK (BTRIM(indicator_code) <> ''),
    CONSTRAINT ck_evaluation_indicator_level CHECK (
        level IS NULL OR level IN ('CRITICAL', 'WARNING', 'NEUTRAL', 'POSITIVE', 'EXCELLENT')
    ),
    CONSTRAINT ck_evaluation_indicator_explanation CHECK (
        BTRIM(explanation) <> '' AND BTRIM(data_source) <> ''
    ),
    CONSTRAINT ck_evaluation_indicator_availability CHECK (
        (calculable = TRUE AND unavailable_reason IS NULL) OR
        (calculable = FALSE AND unavailable_reason IS NOT NULL)
    )
);

CREATE INDEX idx_forecast_run_user_date ON forecast_run(user_id, created_at DESC);
CREATE INDEX idx_forecast_run_status ON forecast_run(status, created_at);
CREATE INDEX idx_forecast_run_account_account ON forecast_run_account(financial_account_id);
CREATE INDEX idx_wellness_evaluation_user_date ON wellness_evaluation(user_id, created_at DESC);
CREATE INDEX idx_wellness_evaluation_status ON wellness_evaluation(status, created_at);
CREATE INDEX idx_wellness_evaluation_account_account
    ON wellness_evaluation_account(financial_account_id);
CREATE INDEX idx_evaluation_dimension_evaluation
    ON evaluation_dimension(wellness_evaluation_id);
CREATE INDEX idx_evaluation_indicator_dimension
    ON evaluation_indicator(evaluation_dimension_id);

CREATE FUNCTION validate_forecast_run_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    plan_user_id UUID;
    plan_currency CHAR(3);
BEGIN
    IF NEW.monthly_plan_id IS NOT NULL THEN
        SELECT user_id, currency INTO plan_user_id, plan_currency
        FROM monthly_plan WHERE id = NEW.monthly_plan_id;

        IF plan_user_id IS DISTINCT FROM NEW.user_id OR plan_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'Forecast and monthly plan must have the same user and currency'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_forecast_run_account_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    run_user_id UUID;
    run_currency CHAR(3);
    run_status VARCHAR(20);
    account_user_id UUID;
    account_currency CHAR(3);
BEGIN
    SELECT user_id, currency, status INTO run_user_id, run_currency, run_status
    FROM forecast_run WHERE id = NEW.forecast_run_id;

    SELECT user_id, currency INTO account_user_id, account_currency
    FROM financial_account WHERE id = NEW.financial_account_id;

    IF run_user_id IS DISTINCT FROM account_user_id OR run_currency IS DISTINCT FROM account_currency THEN
        RAISE EXCEPTION 'Forecast account must have the same user and currency as the forecast'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' AND run_status <> 'QUEUED' THEN
        RAISE EXCEPTION 'Forecast accounts must be selected while the forecast is queued'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND run_status <> 'QUEUED'
       AND ROW(OLD.forecast_run_id, OLD.financial_account_id, OLD.reference_balance, OLD.reference_balance_at)
           IS DISTINCT FROM
           ROW(NEW.forecast_run_id, NEW.financial_account_id, NEW.reference_balance, NEW.reference_balance_at) THEN
        RAISE EXCEPTION 'Forecast account scope and reference values are immutable after execution starts'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_forecast_run_account_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    run_status VARCHAR(20);
BEGIN
    SELECT status INTO run_status FROM forecast_run WHERE id = OLD.forecast_run_id;

    IF run_status <> 'QUEUED' THEN
        RAISE EXCEPTION 'Forecast account scope cannot be removed after execution starts'
            USING ERRCODE = '23514';
    END IF;

    RETURN OLD;
END;
$$;

CREATE FUNCTION validate_wellness_evaluation_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    related_user_id UUID;
    related_currency CHAR(3);
BEGIN
    IF NEW.monthly_plan_id IS NOT NULL THEN
        SELECT user_id, currency INTO related_user_id, related_currency
        FROM monthly_plan WHERE id = NEW.monthly_plan_id;

        IF related_user_id IS DISTINCT FROM NEW.user_id OR related_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'Evaluation and monthly plan must have the same user and currency'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.forecast_run_id IS NOT NULL THEN
        SELECT user_id, currency INTO related_user_id, related_currency
        FROM forecast_run WHERE id = NEW.forecast_run_id;

        IF related_user_id IS DISTINCT FROM NEW.user_id OR related_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'Evaluation and forecast must have the same user and currency'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_wellness_evaluation_account_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    evaluation_user_id UUID;
    evaluation_currency CHAR(3);
    account_user_id UUID;
    account_currency CHAR(3);
BEGIN
    SELECT user_id, currency INTO evaluation_user_id, evaluation_currency
    FROM wellness_evaluation WHERE id = NEW.wellness_evaluation_id;

    SELECT user_id, currency INTO account_user_id, account_currency
    FROM financial_account WHERE id = NEW.financial_account_id;

    IF evaluation_user_id IS DISTINCT FROM account_user_id
       OR evaluation_currency IS DISTINCT FROM account_currency THEN
        RAISE EXCEPTION 'Evaluation account must have the same user and currency as the evaluation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_evaluation_root_immutability()
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
           NEW.coverage_rate, NEW.method_version, NEW.produced_at, NEW.created_at)
           IS NOT DISTINCT FROM
           ROW(OLD.id, OLD.user_id, OLD.monthly_plan_id, OLD.forecast_run_id,
           OLD.period_start, OLD.period_end, OLD.currency, OLD.global_score, OLD.level,
           OLD.coverage_rate, OLD.method_version, OLD.produced_at, OLD.created_at) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'A produced evaluation is immutable; only PRODUCED to OBSOLETE is allowed'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION validate_evaluation_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    dimension_count INTEGER;
    dimension_without_indicator_count INTEGER;
    total_weight NUMERIC;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status IN ('PRODUCED', 'OBSOLETE') THEN
        RAISE EXCEPTION 'Create an evaluation as DRAFT before publishing it'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'PRODUCED' AND OLD.status <> 'PRODUCED' THEN
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

    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_evaluation_child_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    old_evaluation_id UUID;
    new_evaluation_id UUID;
    evaluation_status VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'evaluation_dimension' THEN
        IF TG_OP IN ('UPDATE', 'DELETE') THEN old_evaluation_id := OLD.wellness_evaluation_id; END IF;
        IF TG_OP IN ('INSERT', 'UPDATE') THEN new_evaluation_id := NEW.wellness_evaluation_id; END IF;
    ELSE
        IF TG_OP IN ('UPDATE', 'DELETE') THEN
            SELECT wellness_evaluation_id INTO old_evaluation_id
            FROM evaluation_dimension WHERE id = OLD.evaluation_dimension_id;
        END IF;
        IF TG_OP IN ('INSERT', 'UPDATE') THEN
            SELECT wellness_evaluation_id INTO new_evaluation_id
            FROM evaluation_dimension WHERE id = NEW.evaluation_dimension_id;
        END IF;
    END IF;

    IF old_evaluation_id IS NOT NULL THEN
        SELECT status INTO evaluation_status FROM wellness_evaluation WHERE id = old_evaluation_id;
        IF evaluation_status IN ('PRODUCED', 'OBSOLETE') THEN
            RAISE EXCEPTION 'Produced evaluation dimensions and indicators are immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF new_evaluation_id IS NOT NULL AND new_evaluation_id IS DISTINCT FROM old_evaluation_id THEN
        SELECT status INTO evaluation_status FROM wellness_evaluation WHERE id = new_evaluation_id;
        IF evaluation_status IN ('PRODUCED', 'OBSOLETE') THEN
            RAISE EXCEPTION 'Produced evaluation dimensions and indicators are immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_evaluation_account_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    evaluation_id UUID;
    evaluation_status VARCHAR(20);
BEGIN
    evaluation_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.wellness_evaluation_id ELSE NEW.wellness_evaluation_id END;
    SELECT status INTO evaluation_status FROM wellness_evaluation WHERE id = evaluation_id;

    IF evaluation_status IN ('PRODUCED', 'OBSOLETE') THEN
        RAISE EXCEPTION 'Produced evaluation account scope is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_forecast_run_validate_scope
    BEFORE INSERT OR UPDATE OF user_id, monthly_plan_id, currency ON forecast_run
    FOR EACH ROW EXECUTE FUNCTION validate_forecast_run_scope();
CREATE TRIGGER trg_forecast_run_account_validate_scope
    BEFORE INSERT OR UPDATE ON forecast_run_account
    FOR EACH ROW EXECUTE FUNCTION validate_forecast_run_account_scope();
CREATE TRIGGER trg_forecast_run_account_guard_delete
    BEFORE DELETE ON forecast_run_account
    FOR EACH ROW EXECUTE FUNCTION guard_forecast_run_account_delete();

CREATE TRIGGER trg_a_wellness_evaluation_guard_immutability
    BEFORE UPDATE OR DELETE ON wellness_evaluation
    FOR EACH ROW EXECUTE FUNCTION guard_evaluation_root_immutability();
CREATE TRIGGER trg_b_wellness_evaluation_validate_publication
    BEFORE INSERT OR UPDATE ON wellness_evaluation
    FOR EACH ROW EXECUTE FUNCTION validate_evaluation_publication();
CREATE TRIGGER trg_c_wellness_evaluation_validate_scope
    BEFORE INSERT OR UPDATE OF user_id, monthly_plan_id, forecast_run_id, currency ON wellness_evaluation
    FOR EACH ROW EXECUTE FUNCTION validate_wellness_evaluation_scope();
CREATE TRIGGER trg_wellness_evaluation_account_validate_scope
    BEFORE INSERT OR UPDATE ON wellness_evaluation_account
    FOR EACH ROW EXECUTE FUNCTION validate_wellness_evaluation_account_scope();
CREATE TRIGGER trg_wellness_evaluation_account_guard_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON wellness_evaluation_account
    FOR EACH ROW EXECUTE FUNCTION guard_evaluation_account_immutability();
CREATE TRIGGER trg_evaluation_dimension_guard_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON evaluation_dimension
    FOR EACH ROW EXECUTE FUNCTION guard_evaluation_child_immutability();
CREATE TRIGGER trg_evaluation_indicator_guard_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON evaluation_indicator
    FOR EACH ROW EXECUTE FUNCTION guard_evaluation_child_immutability();

CREATE TRIGGER trg_wellness_evaluation_set_updated_at
    BEFORE UPDATE ON wellness_evaluation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
