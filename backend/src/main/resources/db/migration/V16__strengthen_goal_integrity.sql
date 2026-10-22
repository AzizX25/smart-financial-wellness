CREATE OR REPLACE FUNCTION validate_goal_allocation_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    plan_user_id UUID;
    plan_currency CHAR(3);
    goal_user_id UUID;
    goal_currency CHAR(3);
    goal_status VARCHAR(20);
BEGIN
    SELECT user_id, currency INTO plan_user_id, plan_currency
    FROM monthly_plan WHERE id = NEW.monthly_plan_id;

    SELECT user_id, currency, status INTO goal_user_id, goal_currency, goal_status
    FROM financial_goal WHERE id = NEW.financial_goal_id;

    IF plan_user_id IS DISTINCT FROM goal_user_id OR plan_currency IS DISTINCT FROM goal_currency THEN
        RAISE EXCEPTION 'Goal allocation requires the same user and currency for plan and goal'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('PROPOSED', 'CONFIRMED') AND goal_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Proposed and confirmed allocations require an active goal'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_financial_goal_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'A financial goal owner cannot be changed'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.currency IS DISTINCT FROM OLD.currency
       AND (
           EXISTS (
               SELECT 1 FROM goal_allocation
               WHERE financial_goal_id = OLD.id
           )
           OR EXISTS (
               SELECT 1 FROM goal_contribution
               WHERE financial_goal_id = OLD.id
           )
           OR EXISTS (
               SELECT 1 FROM goal_scenario
               WHERE financial_goal_id = OLD.id
           )
       ) THEN
        RAISE EXCEPTION 'A financial goal currency cannot change after dependent records exist'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_financial_goal_protect_identity
    BEFORE UPDATE OF user_id, currency ON financial_goal
    FOR EACH ROW EXECUTE FUNCTION protect_financial_goal_identity();
