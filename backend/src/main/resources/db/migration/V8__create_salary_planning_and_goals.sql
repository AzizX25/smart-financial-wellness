CREATE TABLE monthly_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    period_month DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    expected_income_total NUMERIC(19, 4) NOT NULL DEFAULT 0,
    recommended_expense_budget NUMERIC(19, 4) NOT NULL DEFAULT 0,
    calculated_saving_capacity NUMERIC(19, 4) NOT NULL DEFAULT 0,
    calculation_method VARCHAR(80) NOT NULL,
    method_version VARCHAR(60) NOT NULL,
    calculated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_monthly_plan UNIQUE (user_id, period_month, currency),
    CONSTRAINT ck_monthly_plan_month CHECK (period_month = DATE_TRUNC('month', period_month)::DATE),
    CONSTRAINT ck_monthly_plan_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_monthly_plan_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED', 'ARCHIVED')),
    CONSTRAINT ck_monthly_plan_amounts CHECK (
        expected_income_total >= 0 AND recommended_expense_budget >= 0
        AND calculated_saving_capacity >= 0
    ),
    CONSTRAINT ck_monthly_plan_method CHECK (
        BTRIM(calculation_method) <> '' AND BTRIM(method_version) <> ''
    )
);

CREATE TABLE planned_income (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monthly_plan_id UUID NOT NULL REFERENCES monthly_plan(id) ON DELETE CASCADE,
    label VARCHAR(160) NOT NULL,
    income_type VARCHAR(30) NOT NULL,
    expected_amount NUMERIC(19, 4) NOT NULL,
    expected_date DATE,
    recurring BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_planned_income_label CHECK (BTRIM(label) <> ''),
    CONSTRAINT ck_planned_income_type CHECK (
        income_type IN ('SALARY', 'FREELANCE', 'BENEFIT', 'GIFT', 'OTHER')
    ),
    CONSTRAINT ck_planned_income_amount CHECK (expected_amount > 0)
);

CREATE TABLE budget_allocation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monthly_plan_id UUID NOT NULL REFERENCES monthly_plan(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    recommended_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    confirmed_amount NUMERIC(19, 4),
    recommendation_origin VARCHAR(30) NOT NULL,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_budget_allocation UNIQUE (monthly_plan_id, category_id),
    CONSTRAINT ck_budget_allocation_origin CHECK (
        recommendation_origin IN ('RULES', 'ML', 'USER', 'HYBRID')
    ),
    CONSTRAINT ck_budget_allocation_amounts CHECK (
        recommended_amount >= 0 AND (confirmed_amount IS NULL OR confirmed_amount >= 0)
    ),
    CONSTRAINT ck_budget_allocation_confirmation CHECK (
        (confirmed_amount IS NULL AND confirmed_at IS NULL) OR
        (confirmed_amount IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE financial_goal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    goal_type VARCHAR(40) NOT NULL,
    currency CHAR(3) NOT NULL,
    target_amount NUMERIC(19, 4) NOT NULL,
    initial_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    target_date DATE,
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_financial_goal_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_financial_goal_type CHECK (
        goal_type IN ('EMERGENCY_FUND', 'PURCHASE', 'TRAVEL', 'EDUCATION',
            'DEBT_REPAYMENT', 'RETIREMENT', 'OTHER')
    ),
    CONSTRAINT ck_financial_goal_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_financial_goal_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_financial_goal_status CHECK (
        status IN ('ACTIVE', 'PAUSED', 'ACHIEVED', 'CANCELLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_financial_goal_amounts CHECK (
        target_amount > 0 AND initial_amount >= 0 AND initial_amount <= target_amount
    )
);

CREATE TABLE goal_allocation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monthly_plan_id UUID NOT NULL REFERENCES monthly_plan(id) ON DELETE CASCADE,
    financial_goal_id UUID NOT NULL REFERENCES financial_goal(id) ON DELETE RESTRICT,
    planned_amount NUMERIC(19, 4) NOT NULL,
    confirmed_amount NUMERIC(19, 4),
    status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_goal_allocation UNIQUE (monthly_plan_id, financial_goal_id),
    CONSTRAINT ck_goal_allocation_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_goal_allocation_amounts CHECK (
        planned_amount >= 0 AND (confirmed_amount IS NULL OR confirmed_amount >= 0)
    ),
    CONSTRAINT ck_goal_allocation_confirmation CHECK (
        (status = 'CONFIRMED' AND confirmed_amount IS NOT NULL AND confirmed_at IS NOT NULL) OR
        (status IN ('PROPOSED', 'CANCELLED') AND confirmed_amount IS NULL AND confirmed_at IS NULL)
    )
);

CREATE TABLE goal_contribution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    financial_goal_id UUID NOT NULL REFERENCES financial_goal(id) ON DELETE RESTRICT,
    transaction_id UUID REFERENCES transaction_record(id) ON DELETE RESTRICT,
    source VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    contribution_date DATE NOT NULL,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_goal_contribution_source CHECK (source IN ('MANUAL', 'TRANSACTION')),
    CONSTRAINT ck_goal_contribution_amount CHECK (amount > 0),
    CONSTRAINT ck_goal_contribution_transaction CHECK (
        (source = 'TRANSACTION' AND transaction_id IS NOT NULL) OR
        (source = 'MANUAL' AND transaction_id IS NULL)
    )
);

CREATE UNIQUE INDEX uk_goal_contribution_transaction
    ON goal_contribution(financial_goal_id, transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE TABLE goal_scenario (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    financial_goal_id UUID NOT NULL REFERENCES financial_goal(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    assumptions JSONB NOT NULL DEFAULT '{}'::JSONB,
    target_amount_snapshot NUMERIC(19, 4) NOT NULL,
    available_amount_snapshot NUMERIC(19, 4) NOT NULL,
    simulated_monthly_contribution NUMERIC(19, 4) NOT NULL,
    simulation_start_date DATE NOT NULL,
    estimated_completion_date DATE,
    estimated_month_count INTEGER,
    method_version VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_goal_scenario_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_goal_scenario_amounts CHECK (
        target_amount_snapshot > 0 AND available_amount_snapshot >= 0
        AND simulated_monthly_contribution > 0
    ),
    CONSTRAINT ck_goal_scenario_month_count CHECK (
        estimated_month_count IS NULL OR estimated_month_count >= 0
    ),
    CONSTRAINT ck_goal_scenario_completion CHECK (
        estimated_completion_date IS NULL OR estimated_completion_date >= simulation_start_date
    ),
    CONSTRAINT ck_goal_scenario_method CHECK (BTRIM(method_version) <> '')
);

CREATE UNIQUE INDEX uk_goal_scenario_name
    ON goal_scenario(financial_goal_id, LOWER(name));
CREATE INDEX idx_monthly_plan_user_month ON monthly_plan(user_id, period_month DESC);
CREATE INDEX idx_planned_income_plan ON planned_income(monthly_plan_id);
CREATE INDEX idx_budget_allocation_plan ON budget_allocation(monthly_plan_id);
CREATE INDEX idx_financial_goal_user_status ON financial_goal(user_id, status);
CREATE INDEX idx_goal_allocation_goal ON goal_allocation(financial_goal_id);
CREATE INDEX idx_goal_contribution_goal_date
    ON goal_contribution(financial_goal_id, contribution_date DESC);
CREATE INDEX idx_goal_scenario_goal_date
    ON goal_scenario(financial_goal_id, created_at DESC);

CREATE FUNCTION validate_budget_allocation_category()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    plan_user_id UUID;
    category_user_id UUID;
BEGIN
    SELECT user_id INTO plan_user_id FROM monthly_plan WHERE id = NEW.monthly_plan_id;
    SELECT user_id INTO category_user_id FROM category WHERE id = NEW.category_id;

    IF category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM plan_user_id THEN
        RAISE EXCEPTION 'Monthly plan cannot use another user''s private category'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_goal_allocation_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    plan_user_id UUID;
    plan_currency CHAR(3);
    goal_user_id UUID;
    goal_currency CHAR(3);
BEGIN
    SELECT user_id, currency INTO plan_user_id, plan_currency
    FROM monthly_plan WHERE id = NEW.monthly_plan_id;

    SELECT user_id, currency INTO goal_user_id, goal_currency
    FROM financial_goal WHERE id = NEW.financial_goal_id;

    IF plan_user_id IS DISTINCT FROM goal_user_id OR plan_currency IS DISTINCT FROM goal_currency THEN
        RAISE EXCEPTION 'Goal allocation requires the same user and currency for plan and goal'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_goal_contribution_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    goal_user_id UUID;
    goal_currency CHAR(3);
    transaction_user_id UUID;
    transaction_currency CHAR(3);
BEGIN
    IF NEW.transaction_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT user_id, currency INTO goal_user_id, goal_currency
    FROM financial_goal WHERE id = NEW.financial_goal_id;

    SELECT user_id, currency INTO transaction_user_id, transaction_currency
    FROM transaction_record WHERE id = NEW.transaction_id;

    IF goal_user_id IS DISTINCT FROM transaction_user_id
       OR goal_currency IS DISTINCT FROM transaction_currency THEN
        RAISE EXCEPTION 'Goal contribution transaction must have the same user and currency as the goal'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION check_confirmed_goal_allocation_total()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    affected_plan_id UUID;
    saving_capacity NUMERIC(19, 4);
    allocated_total NUMERIC(19, 4);
BEGIN
    affected_plan_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.monthly_plan_id ELSE NEW.monthly_plan_id END;

    SELECT calculated_saving_capacity INTO saving_capacity
    FROM monthly_plan WHERE id = affected_plan_id;

    SELECT COALESCE(SUM(confirmed_amount), 0) INTO allocated_total
    FROM goal_allocation
    WHERE monthly_plan_id = affected_plan_id AND status = 'CONFIRMED';

    IF allocated_total > saving_capacity THEN
        RAISE EXCEPTION 'Confirmed goal allocations exceed the monthly saving capacity'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE FUNCTION check_monthly_plan_allocation_capacity()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    allocated_total NUMERIC(19, 4);
BEGIN
    SELECT COALESCE(SUM(confirmed_amount), 0) INTO allocated_total
    FROM goal_allocation
    WHERE monthly_plan_id = NEW.id AND status = 'CONFIRMED';

    IF allocated_total > NEW.calculated_saving_capacity THEN
        RAISE EXCEPTION 'Monthly saving capacity cannot be lower than confirmed goal allocations'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_budget_allocation_validate_category
    BEFORE INSERT OR UPDATE ON budget_allocation
    FOR EACH ROW EXECUTE FUNCTION validate_budget_allocation_category();

CREATE TRIGGER trg_goal_allocation_validate_scope
    BEFORE INSERT OR UPDATE ON goal_allocation
    FOR EACH ROW EXECUTE FUNCTION validate_goal_allocation_scope();

CREATE TRIGGER trg_goal_contribution_validate_scope
    BEFORE INSERT OR UPDATE ON goal_contribution
    FOR EACH ROW EXECUTE FUNCTION validate_goal_contribution_scope();

CREATE CONSTRAINT TRIGGER trg_goal_allocation_check_total
    AFTER INSERT OR UPDATE OR DELETE ON goal_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_confirmed_goal_allocation_total();

CREATE CONSTRAINT TRIGGER trg_monthly_plan_check_capacity
    AFTER UPDATE OF calculated_saving_capacity ON monthly_plan
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_monthly_plan_allocation_capacity();

CREATE TRIGGER trg_monthly_plan_set_updated_at
    BEFORE UPDATE ON monthly_plan
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_planned_income_set_updated_at
    BEFORE UPDATE ON planned_income
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_budget_allocation_set_updated_at
    BEFORE UPDATE ON budget_allocation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_financial_goal_set_updated_at
    BEFORE UPDATE ON financial_goal
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_goal_allocation_set_updated_at
    BEFORE UPDATE ON goal_allocation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_goal_scenario_set_updated_at
    BEFORE UPDATE ON goal_scenario
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
