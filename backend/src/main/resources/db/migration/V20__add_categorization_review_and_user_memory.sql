CREATE TABLE categorization_review (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL
        REFERENCES transaction_record(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL
        REFERENCES app_user(id) ON DELETE RESTRICT,
    state VARCHAR(30) NOT NULL,
    reason_code VARCHAR(60) NOT NULL,
    source_prediction_id UUID
        REFERENCES category_prediction(id) ON DELETE RESTRICT,
    resolution VARCHAR(30),
    resolved_category_id UUID
        REFERENCES category(id) ON DELETE RESTRICT,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_categorization_review_state CHECK (
        state IN ('SUGGESTION_PENDING', 'NEEDS_REVIEW', 'EXCLUDED', 'RESOLVED')
    ),
    CONSTRAINT ck_categorization_review_reason CHECK (
        reason_code = BTRIM(reason_code) AND reason_code ~ '^[A-Z][A-Z0-9_]{1,59}$'
    ),
    CONSTRAINT ck_categorization_review_prediction CHECK (
        state <> 'SUGGESTION_PENDING' OR source_prediction_id IS NOT NULL
    ),
    CONSTRAINT ck_categorization_review_resolution CHECK (
        (state <> 'RESOLVED' AND resolution IS NULL
            AND resolved_category_id IS NULL AND resolved_at IS NULL)
        OR (state = 'RESOLVED'
            AND resolution IN ('SUGGESTION_ACCEPTED', 'MANUAL_ASSIGNMENT')
            AND resolved_category_id IS NOT NULL AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_categorization_review_open_transaction
    ON categorization_review(transaction_id)
    WHERE resolved_at IS NULL;
CREATE UNIQUE INDEX uk_categorization_review_source_prediction
    ON categorization_review(source_prediction_id)
    WHERE source_prediction_id IS NOT NULL;
CREATE INDEX idx_categorization_review_user_state
    ON categorization_review(user_id, state, updated_at DESC, id);

CREATE TABLE categorization_review_candidate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL
        REFERENCES categorization_review(id) ON DELETE RESTRICT,
    category_id UUID NOT NULL
        REFERENCES category(id) ON DELETE RESTRICT,
    candidate_rank SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_categorization_review_candidate_rank UNIQUE (review_id, candidate_rank),
    CONSTRAINT uk_categorization_review_candidate_category UNIQUE (review_id, category_id),
    CONSTRAINT ck_categorization_review_candidate_rank CHECK (candidate_rank BETWEEN 1 AND 3)
);

CREATE INDEX idx_categorization_review_candidate_review
    ON categorization_review_candidate(review_id, candidate_rank);

CREATE TABLE user_merchant_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL
        REFERENCES app_user(id) ON DELETE RESTRICT,
    normalized_merchant_key VARCHAR(160) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    currency CHAR(3) NOT NULL,
    category_id UUID NOT NULL
        REFERENCES category(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_merchant_memory_key
        UNIQUE (user_id, normalized_merchant_key, direction, currency),
    CONSTRAINT ck_user_merchant_memory_key CHECK (
        normalized_merchant_key = BTRIM(normalized_merchant_key)
        AND normalized_merchant_key = LOWER(normalized_merchant_key)
        AND normalized_merchant_key ~ '^[a-z0-9&]+( [a-z0-9&]+)*$'
    ),
    CONSTRAINT ck_user_merchant_memory_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_user_merchant_memory_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    )
);

CREATE INDEX idx_user_merchant_memory_user_updated
    ON user_merchant_memory(user_id, updated_at DESC, id);

ALTER TABLE transaction_record
    DROP CONSTRAINT ck_transaction_category_source,
    ADD CONSTRAINT ck_transaction_category_source CHECK (
        category_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER_MEMORY', 'USER')
    );

ALTER TABLE transaction_category_history
    DROP CONSTRAINT ck_transaction_category_history_source,
    ADD CONSTRAINT ck_transaction_category_history_source CHECK (
        previous_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER_MEMORY', 'USER')
        AND new_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER_MEMORY', 'USER')
    );

CREATE OR REPLACE FUNCTION validate_transaction_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    account_user_id UUID;
    account_currency CHAR(3);
    account_connection_id UUID;
    category_user_id UUID;
    imported_user_id UUID;
    imported_account_id UUID;
    imported_file_id UUID;
    job_connection_id UUID;
    job_status VARCHAR(20);
    prediction_transaction_id UUID;
    prediction_category_id UUID;
BEGIN
    SELECT user_id, currency, bank_connection_id
    INTO account_user_id, account_currency, account_connection_id
    FROM financial_account
    WHERE id = NEW.financial_account_id;

    IF account_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Transaction and financial account must belong to the same user'
            USING ERRCODE = '23514';
    END IF;
    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'Transaction currency must match the financial account currency'
            USING ERRCODE = '23514';
    END IF;

    SELECT user_id INTO category_user_id FROM category WHERE id = NEW.category_id;
    IF category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Transaction category must be system-owned or belong to the transaction user'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.source_type = 'FILE_IMPORT' THEN
        SELECT user_id, financial_account_id, source_file_id
        INTO imported_user_id, imported_account_id, imported_file_id
        FROM import_job WHERE id = NEW.import_job_id;
        IF imported_user_id IS DISTINCT FROM NEW.user_id
           OR imported_account_id IS DISTINCT FROM NEW.financial_account_id
           OR imported_file_id IS DISTINCT FROM NEW.source_file_id THEN
            RAISE EXCEPTION 'Imported transaction must match its import user, account and source file'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.source_type = 'BANK_SYNC' THEN
        SELECT bank_connection_id, status INTO job_connection_id, job_status
        FROM sync_job WHERE id = NEW.sync_job_id;
        IF account_connection_id IS NULL OR account_connection_id IS DISTINCT FROM job_connection_id THEN
            RAISE EXCEPTION 'Synchronized transaction account must belong to the synchronized connection'
                USING ERRCODE = '23514';
        END IF;
        IF job_status NOT IN ('RUNNING', 'SUCCEEDED') THEN
            RAISE EXCEPTION 'Bank transactions require a running or successful synchronization job'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.applied_category_prediction_id IS NOT NULL THEN
        SELECT transaction_id, category_id
        INTO prediction_transaction_id, prediction_category_id
        FROM category_prediction WHERE id = NEW.applied_category_prediction_id;
        IF prediction_transaction_id IS DISTINCT FROM NEW.id
           OR prediction_category_id IS DISTINCT FROM NEW.category_id THEN
            RAISE EXCEPTION 'Applied category prediction must match the transaction and category'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.category_source NOT IN ('RULES', 'ML', 'LLM', 'USER_MEMORY') THEN
            RAISE EXCEPTION 'An applied prediction requires a predictive category source'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.category_source IN ('RULES', 'ML', 'LLM', 'USER_MEMORY') THEN
        RAISE EXCEPTION 'A predictive category source requires an applied prediction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_categorization_review_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    transaction_user_id UUID;
    prediction_transaction_id UUID;
BEGIN
    IF TG_OP = 'UPDATE'
       AND ROW(OLD.transaction_id, OLD.user_id)
           IS DISTINCT FROM ROW(NEW.transaction_id, NEW.user_id) THEN
        RAISE EXCEPTION 'Categorization review ownership is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.state = 'RESOLVED'
       AND ROW(OLD.state, OLD.reason_code, OLD.source_prediction_id, OLD.resolution,
               OLD.resolved_category_id, OLD.resolved_at)
           IS DISTINCT FROM
           ROW(NEW.state, NEW.reason_code, NEW.source_prediction_id, NEW.resolution,
               NEW.resolved_category_id, NEW.resolved_at) THEN
        RAISE EXCEPTION 'Resolved categorization reviews are immutable' USING ERRCODE = '23514';
    END IF;

    SELECT user_id INTO transaction_user_id
    FROM transaction_record WHERE id = NEW.transaction_id;
    IF transaction_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Categorization review must belong to the transaction owner'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.source_prediction_id IS NOT NULL THEN
        SELECT transaction_id INTO prediction_transaction_id
        FROM category_prediction WHERE id = NEW.source_prediction_id;
        IF prediction_transaction_id IS DISTINCT FROM NEW.transaction_id THEN
            RAISE EXCEPTION 'Categorization review prediction must belong to its transaction'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_categorization_review_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    transaction_category_id UUID;
    transaction_source VARCHAR(30);
    transaction_prediction_id UUID;
    prediction_status VARCHAR(20);
BEGIN
    SELECT category_id, category_source, applied_category_prediction_id
    INTO transaction_category_id, transaction_source, transaction_prediction_id
    FROM transaction_record WHERE id = NEW.transaction_id;

    IF NEW.state = 'RESOLVED' THEN
        IF transaction_source = 'UNCATEGORIZED'
           OR transaction_category_id IS DISTINCT FROM NEW.resolved_category_id THEN
            RAISE EXCEPTION 'Resolved review must match the transaction assignment'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.resolution = 'SUGGESTION_ACCEPTED' THEN
            SELECT review_status INTO prediction_status
            FROM category_prediction WHERE id = NEW.source_prediction_id;
            IF NEW.source_prediction_id IS NULL
               OR prediction_status IS DISTINCT FROM 'ACCEPTED'
               OR transaction_prediction_id IS DISTINCT FROM NEW.source_prediction_id
               OR transaction_source NOT IN ('ML', 'LLM', 'USER_MEMORY') THEN
                RAISE EXCEPTION 'Accepted review must match its accepted prediction'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.resolution = 'MANUAL_ASSIGNMENT'
              AND (transaction_source <> 'USER' OR transaction_prediction_id IS NOT NULL) THEN
            RAISE EXCEPTION 'Manual review resolution must match a manual assignment'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF transaction_source <> 'UNCATEGORIZED' THEN
            RAISE EXCEPTION 'An open review requires an uncategorized transaction'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'SUGGESTION_PENDING' THEN
            SELECT review_status INTO prediction_status
            FROM category_prediction WHERE id = NEW.source_prediction_id;
            IF prediction_status IS DISTINCT FROM 'PENDING' THEN
                RAISE EXCEPTION 'Suggestion review requires a pending prediction'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_categorization_review_candidate()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    review_user_id UUID;
    review_state VARCHAR(30);
    transaction_direction VARCHAR(10);
    category_user_id UUID;
    category_direction VARCHAR(10);
    category_active BOOLEAN;
    category_code VARCHAR(60);
BEGIN
    SELECT review.user_id, review.state, transaction.direction
    INTO review_user_id, review_state, transaction_direction
    FROM categorization_review review
    JOIN transaction_record transaction ON transaction.id = review.transaction_id
    WHERE review.id = NEW.review_id;

    SELECT user_id, direction, active, code
    INTO category_user_id, category_direction, category_active, category_code
    FROM category WHERE id = NEW.category_id;

    IF review_state IS DISTINCT FROM 'NEEDS_REVIEW'
       OR category_active IS NOT TRUE
       OR category_code = 'UNCATEGORIZED'
       OR (category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM review_user_id)
       OR (category_direction <> 'BOTH' AND category_direction IS DISTINCT FROM transaction_direction) THEN
        RAISE EXCEPTION 'Review candidate must be an active visible compatible category'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_user_merchant_memory()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    category_user_id UUID;
    category_direction VARCHAR(10);
    category_active BOOLEAN;
    category_code VARCHAR(60);
BEGIN
    IF TG_OP = 'UPDATE'
       AND ROW(OLD.user_id, OLD.normalized_merchant_key, OLD.direction, OLD.currency)
           IS DISTINCT FROM ROW(NEW.user_id, NEW.normalized_merchant_key, NEW.direction, NEW.currency) THEN
        RAISE EXCEPTION 'Merchant memory identity is immutable' USING ERRCODE = '23514';
    END IF;

    SELECT user_id, direction, active, code
    INTO category_user_id, category_direction, category_active, category_code
    FROM category WHERE id = NEW.category_id;
    IF category_active IS NOT TRUE
       OR category_code = 'UNCATEGORIZED'
       OR (category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM NEW.user_id)
       OR (category_direction <> 'BOTH' AND category_direction IS DISTINCT FROM NEW.direction) THEN
        RAISE EXCEPTION 'Merchant memory must reference an active visible compatible category'
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
    IF NEW.category_source = 'LLM' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL OR found_prediction_method <> 'LLM'
        OR found_prediction_review_status <> 'ACCEPTED') THEN
        RAISE EXCEPTION 'LLM-categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'USER_MEMORY' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL OR found_prediction_method <> 'USER_HISTORY'
        OR found_prediction_review_status <> 'ACCEPTED') THEN
        RAISE EXCEPTION 'User-memory-categorized transaction state is inconsistent' USING ERRCODE = '23514';
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

CREATE TRIGGER trg_categorization_review_validate_scope
    BEFORE INSERT OR UPDATE ON categorization_review
    FOR EACH ROW EXECUTE FUNCTION validate_categorization_review_scope();
CREATE CONSTRAINT TRIGGER trg_categorization_review_validate_consistency
    AFTER INSERT OR UPDATE ON categorization_review
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_categorization_review_consistency();
CREATE TRIGGER trg_categorization_review_set_updated_at
    BEFORE UPDATE ON categorization_review
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_categorization_review_candidate_validate
    BEFORE INSERT OR UPDATE ON categorization_review_candidate
    FOR EACH ROW EXECUTE FUNCTION validate_categorization_review_candidate();
CREATE TRIGGER trg_user_merchant_memory_validate
    BEFORE INSERT OR UPDATE ON user_merchant_memory
    FOR EACH ROW EXECUTE FUNCTION validate_user_merchant_memory();
CREATE TRIGGER trg_user_merchant_memory_set_updated_at
    BEFORE UPDATE ON user_merchant_memory
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
