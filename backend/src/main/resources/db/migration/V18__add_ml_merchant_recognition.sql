ALTER TABLE category_prediction
    RENAME COLUMN model_name TO model_id;
ALTER TABLE category_prediction
    RENAME COLUMN confidence TO prediction_score;

ALTER TABLE category_prediction
    ADD COLUMN canonical_merchant VARCHAR(160),
    ADD COLUMN feature_version VARCHAR(100),
    ADD COLUMN review_status VARCHAR(20),
    ADD COLUMN reviewed_at TIMESTAMPTZ;

UPDATE category_prediction
SET review_status = 'AUTO_APPLIED', reviewed_at = produced_at
WHERE prediction_method = 'RULES';

ALTER TABLE category_prediction
    ALTER COLUMN review_status SET NOT NULL,
    DROP CONSTRAINT ck_category_prediction_confidence,
    ADD CONSTRAINT ck_category_prediction_score CHECK (
        prediction_score IS NULL OR prediction_score BETWEEN 0 AND 1
    ),
    ADD CONSTRAINT ck_category_prediction_review_status CHECK (
        review_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_APPLIED')
    ),
    ADD CONSTRAINT ck_category_prediction_review_time CHECK (
        (review_status = 'PENDING' AND reviewed_at IS NULL)
        OR (review_status IN ('ACCEPTED', 'REJECTED', 'AUTO_APPLIED') AND reviewed_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_category_prediction_ml_metadata CHECK (
        prediction_method <> 'ML'
        OR (canonical_merchant IS NOT NULL AND BTRIM(canonical_merchant) <> ''
            AND prediction_score IS NOT NULL
            AND model_id IS NOT NULL AND BTRIM(model_id) <> ''
            AND model_version IS NOT NULL AND BTRIM(model_version) <> ''
            AND feature_version IS NOT NULL AND BTRIM(feature_version) <> '')
    );

CREATE UNIQUE INDEX uk_category_prediction_pending_transaction
    ON category_prediction(transaction_id)
    WHERE review_status = 'PENDING';
CREATE INDEX idx_category_prediction_pending
    ON category_prediction(review_status, produced_at, id)
    WHERE review_status = 'PENDING';

ALTER TABLE category_prediction
    DROP CONSTRAINT category_prediction_transaction_id_fkey,
    ADD CONSTRAINT category_prediction_transaction_id_fkey
        FOREIGN KEY (transaction_id) REFERENCES transaction_record(id) ON DELETE RESTRICT;

ALTER TABLE transaction_category_history
    DROP CONSTRAINT ck_transaction_category_history_source,
    ADD CONSTRAINT ck_transaction_category_history_source CHECK (
        previous_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'USER')
        AND new_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'USER')
    );

CREATE FUNCTION validate_ml_category_prediction()
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
    IF NEW.prediction_method <> 'ML' THEN
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
        RAISE EXCEPTION 'ML prediction is incompatible with transaction or category'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_category_prediction_evidence()
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
           OLD.canonical_merchant, OLD.feature_version)
       IS DISTINCT FROM
       ROW(NEW.transaction_id, NEW.category_id, NEW.prediction_method, NEW.model_id,
           NEW.model_version, NEW.prediction_score, NEW.explanation, NEW.produced_at,
           NEW.canonical_merchant, NEW.feature_version)
       OR OLD.review_status <> 'PENDING'
       OR NEW.review_status NOT IN ('ACCEPTED', 'REJECTED')
       OR NEW.reviewed_at IS NULL THEN
        RAISE EXCEPTION 'Category prediction evidence can only be reviewed once'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

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
    IF NEW.category_source = 'USER' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NOT NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL) THEN
        RAISE EXCEPTION 'Manually categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'LLM' THEN
        RAISE EXCEPTION 'LLM category application is not enabled' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_category_prediction_validate_ml
    BEFORE INSERT OR UPDATE ON category_prediction
    FOR EACH ROW EXECUTE FUNCTION validate_ml_category_prediction();
CREATE TRIGGER trg_category_prediction_evidence
    BEFORE UPDATE OR DELETE ON category_prediction
    FOR EACH ROW EXECUTE FUNCTION guard_category_prediction_evidence();
