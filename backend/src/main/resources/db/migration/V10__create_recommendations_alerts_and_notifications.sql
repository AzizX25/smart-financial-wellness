CREATE TABLE personalized_recommendation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    wellness_evaluation_id UUID NOT NULL REFERENCES wellness_evaluation(id) ON DELETE RESTRICT,
    recommendation_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    informational_disclaimer VARCHAR(500) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    proposed_action_type VARCHAR(50),
    action_parameters JSONB,
    generation_method VARCHAR(20) NOT NULL,
    rules_version VARCHAR(100),
    model_name VARCHAR(100),
    model_version VARCHAR(100),
    llm_model VARCHAR(100),
    llm_version VARCHAR(100),
    confidence NUMERIC(5, 4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_recommendation_text CHECK (
        BTRIM(recommendation_type) <> '' AND BTRIM(title) <> '' AND BTRIM(message) <> ''
        AND BTRIM(informational_disclaimer) <> ''
    ),
    CONSTRAINT ck_recommendation_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_recommendation_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'ACCEPTED', 'REJECTED', 'HIDDEN', 'OBSOLETE', 'EXPIRED')
    ),
    CONSTRAINT ck_recommendation_generation_method CHECK (
        generation_method IN ('RULES', 'ML', 'LLM', 'HYBRID')
    ),
    CONSTRAINT ck_recommendation_generation_trace CHECK (
        (generation_method = 'RULES' AND rules_version IS NOT NULL) OR
        (generation_method = 'ML' AND model_name IS NOT NULL AND model_version IS NOT NULL) OR
        (generation_method = 'LLM' AND llm_model IS NOT NULL AND llm_version IS NOT NULL) OR
        (generation_method = 'HYBRID' AND rules_version IS NOT NULL
            AND ((model_name IS NOT NULL AND model_version IS NOT NULL)
                OR (llm_model IS NOT NULL AND llm_version IS NOT NULL)))
    ),
    CONSTRAINT ck_recommendation_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_recommendation_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_recommendation_close CHECK (closed_at IS NULL OR closed_at >= created_at),
    CONSTRAINT ck_recommendation_action CHECK (
        (proposed_action_type IS NULL AND action_parameters IS NULL) OR proposed_action_type IS NOT NULL
    )
);

CREATE TABLE recommendation_justification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_id UUID NOT NULL REFERENCES personalized_recommendation(id) ON DELETE CASCADE,
    evaluation_indicator_id UUID NOT NULL REFERENCES evaluation_indicator(id) ON DELETE RESTRICT,
    justification_type VARCHAR(40) NOT NULL,
    factual_message VARCHAR(1000) NOT NULL,
    importance NUMERIC(5, 4) NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_recommendation_justification_indicator UNIQUE (
        recommendation_id, evaluation_indicator_id
    ),
    CONSTRAINT ck_recommendation_justification_text CHECK (
        BTRIM(justification_type) <> '' AND BTRIM(factual_message) <> ''
    ),
    CONSTRAINT ck_recommendation_justification_importance CHECK (importance BETWEEN 0 AND 1)
);

CREATE TABLE financial_alert (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    forecast_run_id UUID REFERENCES forecast_run(id) ON DELETE RESTRICT,
    monthly_plan_id UUID REFERENCES monthly_plan(id) ON DELETE RESTRICT,
    financial_goal_id UUID REFERENCES financial_goal(id) ON DELETE RESTRICT,
    financial_account_id UUID REFERENCES financial_account(id) ON DELETE RESTRICT,
    recommendation_id UUID UNIQUE REFERENCES personalized_recommendation(id) ON DELETE RESTRICT,
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    title VARCHAR(200) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    deduplication_key VARCHAR(255) NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL,
    last_detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_financial_alert_source CHECK (
        NUM_NONNULLS(forecast_run_id, monthly_plan_id, financial_goal_id,
            financial_account_id, recommendation_id) = 1
    ),
    CONSTRAINT ck_financial_alert_text CHECK (
        BTRIM(alert_type) <> '' AND BTRIM(title) <> '' AND BTRIM(message) <> ''
        AND BTRIM(deduplication_key) <> ''
    ),
    CONSTRAINT ck_financial_alert_severity CHECK (
        severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_financial_alert_status CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED')
    ),
    CONSTRAINT ck_financial_alert_dates CHECK (
        last_detected_at >= first_detected_at
        AND (resolved_at IS NULL OR resolved_at >= first_detected_at)
    )
);

CREATE UNIQUE INDEX uk_financial_alert_active_deduplication
    ON financial_alert(user_id, deduplication_key)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE TABLE user_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    financial_alert_id UUID REFERENCES financial_alert(id) ON DELETE RESTRICT,
    recommendation_id UUID REFERENCES personalized_recommendation(id) ON DELETE RESTRICT,
    notification_type VARCHAR(60) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    deduplication_key VARCHAR(255) NOT NULL,
    read_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_notification_deduplication UNIQUE (user_id, deduplication_key),
    CONSTRAINT ck_user_notification_source CHECK (
        NUM_NONNULLS(financial_alert_id, recommendation_id) <= 1
    ),
    CONSTRAINT ck_user_notification_text CHECK (
        BTRIM(notification_type) <> '' AND BTRIM(title) <> '' AND BTRIM(message) <> ''
        AND BTRIM(deduplication_key) <> ''
    ),
    CONSTRAINT ck_user_notification_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_user_notification_dates CHECK (
        (read_at IS NULL OR read_at >= created_at)
        AND (expires_at IS NULL OR expires_at > created_at)
    )
);

CREATE INDEX idx_recommendation_user_status_priority
    ON personalized_recommendation(user_id, status, priority);
CREATE INDEX idx_recommendation_justification_recommendation
    ON recommendation_justification(recommendation_id);
CREATE INDEX idx_financial_alert_user_status_severity
    ON financial_alert(user_id, status, severity);
CREATE INDEX idx_user_notification_inbox
    ON user_notification(user_id, read_at, created_at DESC);

CREATE FUNCTION validate_recommendation_scope_and_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    evaluation_user_id UUID;
    evaluation_status VARCHAR(20);
    justification_count INTEGER;
BEGIN
    SELECT user_id, status INTO evaluation_user_id, evaluation_status
    FROM wellness_evaluation WHERE id = NEW.wellness_evaluation_id;

    IF evaluation_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Recommendation and evaluation must belong to the same user'
            USING ERRCODE = '23514';
    END IF;

    IF evaluation_status IS DISTINCT FROM 'PRODUCED' THEN
        RAISE EXCEPTION 'Recommendations require a produced wellness evaluation'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' AND NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'Create a recommendation as DRAFT before activation'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'ACTIVE' AND OLD.status <> 'ACTIVE' THEN
        SELECT COUNT(*) INTO justification_count
        FROM recommendation_justification WHERE recommendation_id = NEW.id;

        IF justification_count = 0 THEN
            RAISE EXCEPTION 'An active recommendation requires at least one valid justification'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_recommendation_justification_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    recommendation_evaluation_id UUID;
    indicator_evaluation_id UUID;
BEGIN
    SELECT wellness_evaluation_id INTO recommendation_evaluation_id
    FROM personalized_recommendation WHERE id = NEW.recommendation_id;

    SELECT dimension.wellness_evaluation_id INTO indicator_evaluation_id
    FROM evaluation_indicator indicator
    JOIN evaluation_dimension dimension ON dimension.id = indicator.evaluation_dimension_id
    WHERE indicator.id = NEW.evaluation_indicator_id;

    IF recommendation_evaluation_id IS DISTINCT FROM indicator_evaluation_id THEN
        RAISE EXCEPTION 'Recommendation justification indicator must belong to the same evaluation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION check_active_recommendation_justification()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    old_recommendation_id UUID;
    new_recommendation_id UUID;
    recommendation_status VARCHAR(20);
    justification_count INTEGER;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_recommendation_id := OLD.recommendation_id;
        SELECT status INTO recommendation_status
        FROM personalized_recommendation WHERE id = old_recommendation_id;

        IF recommendation_status = 'ACTIVE' THEN
            SELECT COUNT(*) INTO justification_count
            FROM recommendation_justification WHERE recommendation_id = old_recommendation_id;
            IF justification_count = 0 THEN
                RAISE EXCEPTION 'An active recommendation must keep at least one justification'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_recommendation_id := NEW.recommendation_id;
        IF new_recommendation_id IS DISTINCT FROM old_recommendation_id THEN
            SELECT status INTO recommendation_status
            FROM personalized_recommendation WHERE id = new_recommendation_id;
            IF recommendation_status = 'ACTIVE' THEN
                SELECT COUNT(*) INTO justification_count
                FROM recommendation_justification WHERE recommendation_id = new_recommendation_id;
                IF justification_count = 0 THEN
                    RAISE EXCEPTION 'An active recommendation requires at least one justification'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_financial_alert_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    related_user_id UUID;
BEGIN
    IF NEW.forecast_run_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM forecast_run WHERE id = NEW.forecast_run_id;
    ELSIF NEW.monthly_plan_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM monthly_plan WHERE id = NEW.monthly_plan_id;
    ELSIF NEW.financial_goal_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM financial_goal WHERE id = NEW.financial_goal_id;
    ELSIF NEW.financial_account_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM financial_account WHERE id = NEW.financial_account_id;
    ELSIF NEW.recommendation_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM personalized_recommendation WHERE id = NEW.recommendation_id;
    END IF;

    IF related_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Financial alert and related resource must belong to the same user'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_user_notification_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    related_user_id UUID;
BEGIN
    IF NEW.financial_alert_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM financial_alert WHERE id = NEW.financial_alert_id;
    ELSIF NEW.recommendation_id IS NOT NULL THEN
        SELECT user_id INTO related_user_id FROM personalized_recommendation WHERE id = NEW.recommendation_id;
    ELSE
        RETURN NEW;
    END IF;

    IF related_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'User notification and related resource must belong to the same user'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_recommendation_validate_scope_activation
    BEFORE INSERT OR UPDATE ON personalized_recommendation
    FOR EACH ROW EXECUTE FUNCTION validate_recommendation_scope_and_activation();
CREATE TRIGGER trg_recommendation_set_updated_at
    BEFORE UPDATE ON personalized_recommendation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_recommendation_justification_validate_scope
    BEFORE INSERT OR UPDATE ON recommendation_justification
    FOR EACH ROW EXECUTE FUNCTION validate_recommendation_justification_scope();
CREATE CONSTRAINT TRIGGER trg_recommendation_justification_check_active
    AFTER INSERT OR UPDATE OR DELETE ON recommendation_justification
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_active_recommendation_justification();
CREATE TRIGGER trg_financial_alert_validate_scope
    BEFORE INSERT OR UPDATE ON financial_alert
    FOR EACH ROW EXECUTE FUNCTION validate_financial_alert_scope();
CREATE TRIGGER trg_financial_alert_set_updated_at
    BEFORE UPDATE ON financial_alert
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_user_notification_validate_scope
    BEFORE INSERT OR UPDATE ON user_notification
    FOR EACH ROW EXECUTE FUNCTION validate_user_notification_scope();
