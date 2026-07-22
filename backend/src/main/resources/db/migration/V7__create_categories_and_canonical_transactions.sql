CREATE TABLE category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES app_user(id) ON DELETE RESTRICT,
    parent_id UUID REFERENCES category(id) ON DELETE RESTRICT,
    code VARCHAR(60) NOT NULL,
    label VARCHAR(160) NOT NULL,
    taxonomy_version VARCHAR(40) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_category_code CHECK (BTRIM(code) <> ''),
    CONSTRAINT ck_category_label CHECK (BTRIM(label) <> ''),
    CONSTRAINT ck_category_taxonomy_version CHECK (BTRIM(taxonomy_version) <> ''),
    CONSTRAINT ck_category_direction CHECK (direction IN ('CREDIT', 'DEBIT', 'BOTH')),
    CONSTRAINT ck_category_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE UNIQUE INDEX uk_category_system_code
    ON category(LOWER(code))
    WHERE user_id IS NULL;
CREATE UNIQUE INDEX uk_category_user_code
    ON category(user_id, LOWER(code))
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_category_parent ON category(parent_id);
CREATE INDEX idx_category_user_active ON category(user_id, active);

INSERT INTO category (id, code, label, taxonomy_version, direction) VALUES
    ('00000000-0000-0000-0000-000000000001', 'UNCATEGORIZED', 'Uncategorized', 'v1', 'BOTH'),
    ('00000000-0000-0000-0000-000000000002', 'INCOME_SALARY', 'Salary', 'v1', 'CREDIT'),
    ('00000000-0000-0000-0000-000000000003', 'INCOME_OTHER', 'Other income', 'v1', 'CREDIT'),
    ('00000000-0000-0000-0000-000000000004', 'HOUSING', 'Housing', 'v1', 'DEBIT'),
    ('00000000-0000-0000-0000-000000000005', 'FOOD', 'Food', 'v1', 'DEBIT'),
    ('00000000-0000-0000-0000-000000000006', 'TRANSPORT', 'Transport', 'v1', 'DEBIT'),
    ('00000000-0000-0000-0000-000000000007', 'HEALTH', 'Health', 'v1', 'DEBIT'),
    ('00000000-0000-0000-0000-000000000008', 'SAVINGS', 'Savings', 'v1', 'BOTH'),
    ('00000000-0000-0000-0000-000000000009', 'TRANSFER_IN', 'Incoming transfer', 'v1', 'CREDIT'),
    ('00000000-0000-0000-0000-000000000010', 'TRANSFER_OUT', 'Outgoing transfer', 'v1', 'DEBIT');

CREATE TABLE transaction_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    financial_account_id UUID NOT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    applied_category_prediction_id UUID,
    source_type VARCHAR(20) NOT NULL,
    import_job_id UUID REFERENCES import_job(id) ON DELETE RESTRICT,
    source_file_id UUID REFERENCES source_file(id) ON DELETE RESTRICT,
    source_row_number INTEGER,
    sync_job_id UUID REFERENCES sync_job(id) ON DELETE RESTRICT,
    external_transaction_id VARCHAR(255),
    client_idempotency_key VARCHAR(160),
    fingerprint CHAR(64),
    booking_date DATE NOT NULL,
    value_date DATE,
    amount NUMERIC(19, 4) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    currency CHAR(3) NOT NULL,
    raw_description VARCHAR(1000) NOT NULL,
    normalized_description VARCHAR(1000) NOT NULL,
    recognized_merchant VARCHAR(255),
    category_source VARCHAR(30) NOT NULL DEFAULT 'UNCATEGORIZED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_transaction_source CHECK (source_type IN ('MANUAL', 'FILE_IMPORT', 'BANK_SYNC')),
    CONSTRAINT ck_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_transaction_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_transaction_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_transaction_descriptions CHECK (
        BTRIM(raw_description) <> '' AND BTRIM(normalized_description) <> ''
    ),
    CONSTRAINT ck_transaction_source_row CHECK (source_row_number IS NULL OR source_row_number > 0),
    CONSTRAINT ck_transaction_fingerprint CHECK (fingerprint IS NULL OR fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_category_source CHECK (
        category_source IN ('UNCATEGORIZED', 'RULES', 'ML', 'LLM', 'USER')
    ),
    CONSTRAINT ck_transaction_provenance CHECK (
        (source_type = 'MANUAL'
            AND import_job_id IS NULL AND source_file_id IS NULL AND source_row_number IS NULL
            AND sync_job_id IS NULL AND external_transaction_id IS NULL) OR
        (source_type = 'FILE_IMPORT'
            AND import_job_id IS NOT NULL AND source_file_id IS NOT NULL AND source_row_number IS NOT NULL
            AND sync_job_id IS NULL AND external_transaction_id IS NULL
            AND client_idempotency_key IS NULL) OR
        (source_type = 'BANK_SYNC'
            AND import_job_id IS NULL AND source_file_id IS NULL AND source_row_number IS NULL
            AND sync_job_id IS NOT NULL AND external_transaction_id IS NOT NULL
            AND client_idempotency_key IS NULL)
    )
);

CREATE UNIQUE INDEX uk_transaction_file_row
    ON transaction_record(financial_account_id, source_file_id, source_row_number)
    WHERE source_type = 'FILE_IMPORT';
CREATE UNIQUE INDEX uk_transaction_manual_idempotency
    ON transaction_record(user_id, client_idempotency_key)
    WHERE source_type = 'MANUAL' AND client_idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uk_transaction_bank_external
    ON transaction_record(financial_account_id, external_transaction_id)
    WHERE source_type = 'BANK_SYNC';
CREATE INDEX idx_transaction_account_date
    ON transaction_record(financial_account_id, booking_date DESC);
CREATE INDEX idx_transaction_user_date
    ON transaction_record(user_id, booking_date DESC);
CREATE INDEX idx_transaction_category_date
    ON transaction_record(category_id, booking_date DESC);
CREATE INDEX idx_transaction_fingerprint
    ON transaction_record(financial_account_id, fingerprint)
    WHERE fingerprint IS NOT NULL;

CREATE TABLE category_prediction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transaction_record(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    prediction_method VARCHAR(30) NOT NULL,
    model_name VARCHAR(100),
    model_version VARCHAR(100),
    confidence NUMERIC(5, 4),
    explanation VARCHAR(1000),
    produced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_category_prediction_method CHECK (
        prediction_method IN ('RULES', 'ML', 'LLM', 'USER_HISTORY')
    ),
    CONSTRAINT ck_category_prediction_model CHECK (
        (prediction_method IN ('ML', 'LLM', 'USER_HISTORY')
            AND model_name IS NOT NULL AND model_version IS NOT NULL) OR
        prediction_method = 'RULES'
    ),
    CONSTRAINT ck_category_prediction_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 1
    )
);

ALTER TABLE transaction_record
    ADD CONSTRAINT fk_transaction_applied_category_prediction
    FOREIGN KEY (applied_category_prediction_id)
    REFERENCES category_prediction(id)
    ON DELETE SET NULL;

CREATE INDEX idx_category_prediction_transaction_date
    ON category_prediction(transaction_id, produced_at DESC);

CREATE TABLE internal_transfer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outgoing_transaction_id UUID NOT NULL UNIQUE
        REFERENCES transaction_record(id) ON DELETE RESTRICT,
    incoming_transaction_id UUID NOT NULL UNIQUE
        REFERENCES transaction_record(id) ON DELETE RESTRICT,
    detection_method VARCHAR(30) NOT NULL,
    confidence NUMERIC(5, 4),
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_internal_transfer_distinct CHECK (
        outgoing_transaction_id <> incoming_transaction_id
    ),
    CONSTRAINT ck_internal_transfer_method CHECK (detection_method IN ('RULES', 'ML', 'USER')),
    CONSTRAINT ck_internal_transfer_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 1
    )
);

CREATE FUNCTION validate_category_parent_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    parent_user_id UUID;
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT user_id INTO parent_user_id
    FROM category
    WHERE id = NEW.parent_id;

    IF NEW.user_id IS NULL AND parent_user_id IS NOT NULL THEN
        RAISE EXCEPTION 'A system category cannot have a private parent'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.user_id IS NOT NULL
       AND parent_user_id IS NOT NULL
       AND parent_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'A private category cannot use another user''s private category as parent'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_transaction_scope()
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

    SELECT user_id INTO category_user_id
    FROM category
    WHERE id = NEW.category_id;

    IF category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Transaction category must be system-owned or belong to the transaction user'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.source_type = 'FILE_IMPORT' THEN
        SELECT user_id, financial_account_id, source_file_id
        INTO imported_user_id, imported_account_id, imported_file_id
        FROM import_job
        WHERE id = NEW.import_job_id;

        IF imported_user_id IS DISTINCT FROM NEW.user_id
           OR imported_account_id IS DISTINCT FROM NEW.financial_account_id
           OR imported_file_id IS DISTINCT FROM NEW.source_file_id THEN
            RAISE EXCEPTION 'Imported transaction must match its import user, account and source file'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.source_type = 'BANK_SYNC' THEN
        SELECT bank_connection_id, status
        INTO job_connection_id, job_status
        FROM sync_job
        WHERE id = NEW.sync_job_id;

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
        FROM category_prediction
        WHERE id = NEW.applied_category_prediction_id;

        IF prediction_transaction_id IS DISTINCT FROM NEW.id
           OR prediction_category_id IS DISTINCT FROM NEW.category_id THEN
            RAISE EXCEPTION 'Applied category prediction must match the transaction and category'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.category_source NOT IN ('RULES', 'ML', 'LLM') THEN
            RAISE EXCEPTION 'An applied prediction requires a predictive category source'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.category_source IN ('RULES', 'ML', 'LLM') THEN
        RAISE EXCEPTION 'A predictive category source requires an applied prediction'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_category_prediction_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    transaction_user_id UUID;
    category_user_id UUID;
BEGIN
    SELECT user_id INTO transaction_user_id
    FROM transaction_record
    WHERE id = NEW.transaction_id;

    SELECT user_id INTO category_user_id
    FROM category
    WHERE id = NEW.category_id;

    IF category_user_id IS NOT NULL AND category_user_id IS DISTINCT FROM transaction_user_id THEN
        RAISE EXCEPTION 'Category prediction cannot use another user''s private category'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_internal_transfer_pair()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    outgoing_user_id UUID;
    outgoing_account_id UUID;
    outgoing_currency CHAR(3);
    outgoing_amount NUMERIC(19, 4);
    outgoing_direction VARCHAR(10);
    incoming_user_id UUID;
    incoming_account_id UUID;
    incoming_currency CHAR(3);
    incoming_amount NUMERIC(19, 4);
    incoming_direction VARCHAR(10);
BEGIN
    SELECT user_id, financial_account_id, currency, amount, direction
    INTO outgoing_user_id, outgoing_account_id, outgoing_currency, outgoing_amount, outgoing_direction
    FROM transaction_record
    WHERE id = NEW.outgoing_transaction_id;

    SELECT user_id, financial_account_id, currency, amount, direction
    INTO incoming_user_id, incoming_account_id, incoming_currency, incoming_amount, incoming_direction
    FROM transaction_record
    WHERE id = NEW.incoming_transaction_id;

    IF outgoing_user_id IS DISTINCT FROM incoming_user_id
       OR outgoing_currency IS DISTINCT FROM incoming_currency
       OR outgoing_amount IS DISTINCT FROM incoming_amount
       OR outgoing_account_id IS NOT DISTINCT FROM incoming_account_id
       OR outgoing_direction IS DISTINCT FROM 'DEBIT'
       OR incoming_direction IS DISTINCT FROM 'CREDIT' THEN
        RAISE EXCEPTION 'Internal transfer requires same-user, same-currency, equal-amount transactions on distinct accounts with opposite directions'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_linked_internal_transfer_transaction()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF ROW(OLD.user_id, OLD.financial_account_id, OLD.currency, OLD.amount, OLD.direction)
       IS DISTINCT FROM
       ROW(NEW.user_id, NEW.financial_account_id, NEW.currency, NEW.amount, NEW.direction)
       AND EXISTS (
           SELECT 1 FROM internal_transfer
           WHERE outgoing_transaction_id = OLD.id OR incoming_transaction_id = OLD.id
       ) THEN
        RAISE EXCEPTION 'A transaction linked to an internal transfer cannot change its pairing fields'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_category_validate_parent
    BEFORE INSERT OR UPDATE OF user_id, parent_id ON category
    FOR EACH ROW EXECUTE FUNCTION validate_category_parent_scope();

CREATE TRIGGER trg_category_set_updated_at
    BEFORE UPDATE ON category
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_transaction_guard_internal_transfer
    BEFORE UPDATE OF user_id, financial_account_id, currency, amount, direction ON transaction_record
    FOR EACH ROW EXECUTE FUNCTION guard_linked_internal_transfer_transaction();

CREATE TRIGGER trg_transaction_validate_scope
    BEFORE INSERT OR UPDATE ON transaction_record
    FOR EACH ROW EXECUTE FUNCTION validate_transaction_scope();

CREATE TRIGGER trg_transaction_set_updated_at
    BEFORE UPDATE ON transaction_record
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_category_prediction_validate_scope
    BEFORE INSERT OR UPDATE ON category_prediction
    FOR EACH ROW EXECUTE FUNCTION validate_category_prediction_scope();

CREATE TRIGGER trg_internal_transfer_validate_pair
    BEFORE INSERT OR UPDATE ON internal_transfer
    FOR EACH ROW EXECUTE FUNCTION validate_internal_transfer_pair();
