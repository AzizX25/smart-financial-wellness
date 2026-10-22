ALTER TABLE category_prediction
    ADD COLUMN provider_id VARCHAR(60),
    ADD CONSTRAINT ck_category_prediction_llm_metadata CHECK (
        (prediction_method = 'LLM'
            AND canonical_merchant IS NOT NULL AND BTRIM(canonical_merchant) <> ''
            AND prediction_score IS NULL
            AND provider_id IS NOT NULL AND BTRIM(provider_id) <> ''
            AND model_id IS NOT NULL AND BTRIM(model_id) <> ''
            AND model_version IS NOT NULL AND BTRIM(model_version) <> ''
            AND feature_version IS NOT NULL AND BTRIM(feature_version) <> ''
            AND explanation IS NOT NULL AND BTRIM(explanation) <> '')
        OR (prediction_method <> 'LLM' AND provider_id IS NULL)
    );

CREATE TABLE category_prediction_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prediction_id UUID NOT NULL
        REFERENCES category_prediction(id) ON DELETE RESTRICT,
    evidence_order SMALLINT NOT NULL,
    evidence_url VARCHAR(2048) NOT NULL,
    produced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_category_prediction_evidence_order
        UNIQUE (prediction_id, evidence_order),
    CONSTRAINT uk_category_prediction_evidence_url
        UNIQUE (prediction_id, evidence_url),
    CONSTRAINT ck_category_prediction_evidence_order CHECK (
        evidence_order BETWEEN 1 AND 3
    ),
    CONSTRAINT ck_category_prediction_evidence_url CHECK (
        evidence_url = BTRIM(evidence_url)
        AND evidence_url ~ '^https://[^[:space:]]+$'
    )
);

CREATE INDEX idx_category_prediction_evidence_prediction
    ON category_prediction_evidence(prediction_id, evidence_order);

CREATE FUNCTION validate_category_prediction_evidence_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    found_method VARCHAR(30);
    found_review_status VARCHAR(20);
BEGIN
    SELECT prediction_method, review_status
    INTO found_method, found_review_status
    FROM category_prediction
    WHERE id = NEW.prediction_id;

    IF found_method IS DISTINCT FROM 'LLM' OR found_review_status IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION 'Evidence may only be added to a pending LLM prediction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_category_prediction_evidence_row()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'Category prediction evidence is append-only' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION require_llm_prediction_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF NEW.prediction_method = 'LLM'
       AND NOT EXISTS (
           SELECT 1 FROM category_prediction_evidence evidence
           WHERE evidence.prediction_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'An LLM prediction requires persisted evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_category_prediction_evidence_insert
    BEFORE INSERT ON category_prediction_evidence
    FOR EACH ROW EXECUTE FUNCTION validate_category_prediction_evidence_insert();
CREATE TRIGGER trg_category_prediction_evidence_append_only
    BEFORE UPDATE OR DELETE ON category_prediction_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_category_prediction_evidence_row();
CREATE CONSTRAINT TRIGGER trg_category_prediction_llm_has_evidence
    AFTER INSERT OR UPDATE ON category_prediction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_llm_prediction_evidence();

CREATE OR REPLACE FUNCTION validate_ml_category_prediction()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    transaction_direction VARCHAR(10);
    transaction_currency CHAR(3);
    category_code VARCHAR(60);
    category_direction VARCHAR(10);
    category_user_id UUID;
    category_active BOOLEAN;
BEGIN
    IF NEW.prediction_method NOT IN ('ML', 'LLM') THEN
        RETURN NEW;
    END IF;
    SELECT direction, currency INTO transaction_direction, transaction_currency
    FROM transaction_record WHERE id = NEW.transaction_id;
    SELECT code, direction, user_id, active
    INTO category_code, category_direction, category_user_id, category_active
    FROM category WHERE id = NEW.category_id;
    IF transaction_direction <> 'DEBIT' OR transaction_currency <> 'TND'
       OR category_user_id IS NOT NULL OR category_active IS NOT TRUE
       OR category_direction <> 'DEBIT'
       OR category_code NOT IN ('FOOD', 'BILLS_SUBSCRIPTIONS', 'TRANSPORT', 'HEALTH',
            'SHOPPING', 'LEISURE', 'EDUCATION', 'HOUSING', 'BANK_FEES', 'TAXES') THEN
        RAISE EXCEPTION 'AI prediction is incompatible with transaction or category'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_category_prediction_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Category prediction evidence is append-only' USING ERRCODE = '23514';
    END IF;
    IF ROW(OLD.transaction_id, OLD.category_id, OLD.prediction_method, OLD.model_id,
           OLD.model_version, OLD.prediction_score, OLD.explanation, OLD.produced_at,
           OLD.canonical_merchant, OLD.feature_version, OLD.provider_id)
       IS DISTINCT FROM
       ROW(NEW.transaction_id, NEW.category_id, NEW.prediction_method, NEW.model_id,
           NEW.model_version, NEW.prediction_score, NEW.explanation, NEW.produced_at,
           NEW.canonical_merchant, NEW.feature_version, NEW.provider_id)
       OR OLD.review_status <> 'PENDING'
       OR NEW.review_status NOT IN ('ACCEPTED', 'REJECTED')
       OR NEW.reviewed_at IS NULL THEN
        RAISE EXCEPTION 'Category prediction evidence can only be reviewed once'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE transaction_category_history
    DROP CONSTRAINT ck_transaction_category_history_source,
    ADD CONSTRAINT ck_transaction_category_history_source CHECK (
        previous_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER')
        AND new_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER')
    );

CREATE OR REPLACE FUNCTION validate_transaction_category_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    category_direction VARCHAR(10);
    rule_category_id UUID;
    found_prediction_category_id UUID;
    found_prediction_method VARCHAR(30);
    found_prediction_review_status VARCHAR(20);
BEGIN
    SELECT direction INTO category_direction FROM category WHERE id = NEW.category_id;
    IF category_direction <> 'BOTH' AND category_direction IS DISTINCT FROM NEW.direction THEN
        RAISE EXCEPTION 'Transaction direction is incompatible with category direction' USING ERRCODE = '23514';
    END IF;
    IF NEW.matched_categorization_rule_id IS NOT NULL THEN
        SELECT category_id INTO rule_category_id FROM categorization_rule
        WHERE id = NEW.matched_categorization_rule_id AND active;
        IF rule_category_id IS DISTINCT FROM NEW.category_id THEN
            RAISE EXCEPTION 'Matched categorization rule must be active and target the assigned category' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.applied_category_prediction_id IS NOT NULL THEN
        SELECT prediction.category_id, prediction.prediction_method, prediction.review_status
        INTO found_prediction_category_id, found_prediction_method, found_prediction_review_status
        FROM category_prediction prediction WHERE prediction.id = NEW.applied_category_prediction_id;
        IF found_prediction_category_id IS DISTINCT FROM NEW.category_id THEN
            RAISE EXCEPTION 'Applied prediction must target the assigned category' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.category_source = 'UNCATEGORIZED' AND
       (NEW.category_id <> '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NOT NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Uncategorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'RULES' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL OR NEW.matched_categorization_rule_id IS NULL
        OR NEW.categorized_at IS NULL OR found_prediction_method <> 'RULES') THEN
        RAISE EXCEPTION 'Rule-categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'ML' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL OR found_prediction_method <> 'ML'
        OR found_prediction_review_status <> 'ACCEPTED') THEN
        RAISE EXCEPTION 'ML-categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'LLM' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL OR found_prediction_method <> 'LLM'
        OR found_prediction_review_status <> 'ACCEPTED') THEN
        RAISE EXCEPTION 'LLM-categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'USER' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NOT NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL) THEN
        RAISE EXCEPTION 'Manually categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
