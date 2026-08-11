ALTER TABLE monthly_plan
    DROP CONSTRAINT ck_monthly_plan_amounts,
    ADD CONSTRAINT ck_monthly_plan_amounts CHECK (
        expected_income_total >= 0 AND recommended_expense_budget >= 0
    );

ALTER TABLE planned_income
    DROP CONSTRAINT ck_planned_income_amount,
    ADD CONSTRAINT ck_planned_income_amount CHECK (expected_amount >= 0);

CREATE OR REPLACE FUNCTION validate_budget_allocation_category()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    plan_user_id UUID;
    category_user_id UUID;
    category_nature VARCHAR(10);
    category_active BOOLEAN;
BEGIN
    SELECT user_id INTO plan_user_id
    FROM monthly_plan
    WHERE id = NEW.monthly_plan_id;

    SELECT user_id, nature, active
    INTO category_user_id, category_nature, category_active
    FROM category
    WHERE id = NEW.category_id;

    IF category_active IS DISTINCT FROM TRUE
       OR category_nature IS DISTINCT FROM 'EXPENSE'
       OR (category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM plan_user_id) THEN
        RAISE EXCEPTION 'Monthly plan requires an active, visible expense category'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION check_confirmed_goal_allocation_total()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    affected_plan_id UUID;
    saving_capacity NUMERIC(19, 4);
    available_capacity NUMERIC(19, 4);
    allocated_total NUMERIC(19, 4);
BEGIN
    affected_plan_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.monthly_plan_id ELSE NEW.monthly_plan_id END;

    SELECT calculated_saving_capacity INTO saving_capacity
    FROM monthly_plan WHERE id = affected_plan_id;

    available_capacity := GREATEST(saving_capacity, 0);

    SELECT COALESCE(SUM(confirmed_amount), 0) INTO allocated_total
    FROM goal_allocation
    WHERE monthly_plan_id = affected_plan_id AND status = 'CONFIRMED';

    IF allocated_total > available_capacity THEN
        RAISE EXCEPTION 'Confirmed goal allocations exceed the monthly saving capacity'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION check_monthly_plan_allocation_capacity()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    available_capacity NUMERIC(19, 4);
    allocated_total NUMERIC(19, 4);
BEGIN
    available_capacity := GREATEST(NEW.calculated_saving_capacity, 0);

    SELECT COALESCE(SUM(confirmed_amount), 0) INTO allocated_total
    FROM goal_allocation
    WHERE monthly_plan_id = NEW.id AND status = 'CONFIRMED';

    IF allocated_total > available_capacity THEN
        RAISE EXCEPTION 'Monthly saving capacity cannot be lower than confirmed goal allocations'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;
