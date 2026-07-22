CREATE TABLE bank_connection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    provider_code VARCHAR(60) NOT NULL,
    external_customer_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_bank_connection_provider CHECK (BTRIM(provider_code) <> ''),
    CONSTRAINT ck_bank_connection_external_customer CHECK (
        external_customer_id IS NULL OR BTRIM(external_customer_id) <> ''
    ),
    CONSTRAINT ck_bank_connection_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'REVOKED', 'ERROR', 'DISCONNECTED')
    )
);

CREATE UNIQUE INDEX uk_bank_connection_external_customer
    ON bank_connection(user_id, provider_code, external_customer_id)
    WHERE external_customer_id IS NOT NULL;

CREATE TABLE bank_connection_authorization (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_connection_id UUID NOT NULL REFERENCES bank_connection(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL,
    secret_reference VARCHAR(500),
    granted_scopes VARCHAR(40)[] NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(255),
    superseded_at TIMESTAMPTZ,
    superseded_by_authorization_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bank_authorization_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'SUPERSEDED')
    ),
    CONSTRAINT ck_bank_authorization_scopes CHECK (
        CARDINALITY(granted_scopes) > 0 AND
        granted_scopes <@ ARRAY['ACCOUNTS_READ', 'BALANCES_READ', 'TRANSACTIONS_READ']::VARCHAR(40)[]
    ),
    CONSTRAINT ck_bank_authorization_secret_reference CHECK (
        secret_reference IS NULL OR BTRIM(secret_reference) <> ''
    ),
    CONSTRAINT ck_bank_authorization_expiry CHECK (expires_at > consented_at),
    CONSTRAINT ck_bank_authorization_state CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL
            AND superseded_at IS NULL AND superseded_by_authorization_id IS NULL) OR
        (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= consented_at
            AND revocation_reason IS NOT NULL AND superseded_at IS NULL
            AND superseded_by_authorization_id IS NULL) OR
        (status = 'EXPIRED' AND revoked_at IS NULL AND revocation_reason IS NULL
            AND superseded_at IS NULL AND superseded_by_authorization_id IS NULL) OR
        (status = 'SUPERSEDED' AND revoked_at IS NULL AND revocation_reason IS NULL
            AND superseded_at IS NOT NULL AND superseded_at >= consented_at
            AND superseded_by_authorization_id IS NOT NULL)
    ),
    CONSTRAINT fk_bank_authorization_successor
        FOREIGN KEY (superseded_by_authorization_id)
        REFERENCES bank_connection_authorization(id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX uk_bank_authorization_active_connection
    ON bank_connection_authorization(bank_connection_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_bank_authorization_connection_history
    ON bank_connection_authorization(bank_connection_id, consented_at DESC);

CREATE TABLE financial_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    bank_connection_id UUID REFERENCES bank_connection(id) ON DELETE RESTRICT,
    account_origin VARCHAR(20) NOT NULL,
    external_account_id VARCHAR(255),
    name VARCHAR(160) NOT NULL,
    masked_identifier VARCHAR(80),
    account_type VARCHAR(30) NOT NULL,
    currency CHAR(3) NOT NULL,
    current_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_financial_account_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_financial_account_type CHECK (
        account_type IN ('CHECKING', 'SAVINGS', 'CREDIT', 'CASH', 'OTHER')
    ),
    CONSTRAINT ck_financial_account_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_financial_account_status CHECK (
        status IN ('ACTIVE', 'ARCHIVED', 'DISCONNECTED')
    ),
    CONSTRAINT ck_financial_account_origin CHECK (
        (account_origin = 'MANUAL' AND bank_connection_id IS NULL AND external_account_id IS NULL) OR
        (account_origin = 'BANK_CONNECTION' AND bank_connection_id IS NOT NULL
            AND external_account_id IS NOT NULL AND BTRIM(external_account_id) <> '')
    )
);

CREATE UNIQUE INDEX uk_financial_account_connection_external
    ON financial_account(bank_connection_id, external_account_id)
    WHERE bank_connection_id IS NOT NULL AND external_account_id IS NOT NULL;
CREATE INDEX idx_financial_account_user_status
    ON financial_account(user_id, status);

CREATE TABLE sync_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_connection_id UUID NOT NULL REFERENCES bank_connection(id) ON DELETE RESTRICT,
    authorization_id UUID NOT NULL REFERENCES bank_connection_authorization(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    synchronization_cursor VARCHAR(500),
    read_count INTEGER NOT NULL DEFAULT 0,
    created_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    safe_error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sync_job_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_sync_job_counts CHECK (
        read_count >= 0 AND created_count >= 0 AND duplicate_count >= 0 AND rejected_count >= 0
    ),
    CONSTRAINT ck_sync_job_dates CHECK (
        (started_at IS NULL OR started_at >= created_at) AND
        (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at))
    ),
    CONSTRAINT ck_sync_job_running_started CHECK (
        status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') OR started_at IS NOT NULL
    ),
    CONSTRAINT ck_sync_job_terminal_completed CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR completed_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX uk_sync_job_active_connection
    ON sync_job(bank_connection_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_sync_job_connection_date
    ON sync_job(bank_connection_id, created_at DESC);

CREATE TABLE balance_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    financial_account_id UUID NOT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    sync_job_id UUID REFERENCES sync_job(id) ON DELETE RESTRICT,
    available_balance NUMERIC(19, 4),
    ledger_balance NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_balance_snapshot UNIQUE (financial_account_id, observed_at, source_type),
    CONSTRAINT ck_balance_snapshot_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_balance_snapshot_source CHECK (
        source_type IN ('MANUAL', 'FILE_IMPORT', 'BANK_SYNC')
    ),
    CONSTRAINT ck_balance_snapshot_provenance CHECK (
        (source_type = 'BANK_SYNC' AND sync_job_id IS NOT NULL) OR
        (source_type IN ('MANUAL', 'FILE_IMPORT') AND sync_job_id IS NULL)
    )
);

CREATE INDEX idx_balance_snapshot_account_date
    ON balance_snapshot(financial_account_id, observed_at DESC);

CREATE FUNCTION validate_bank_authorization_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        IF NEW.expires_at <= CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION 'An active bank authorization cannot already be expired'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.secret_reference IS NULL THEN
            RAISE EXCEPTION 'An active bank authorization requires a secret reference'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_financial_account_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    connection_user_id UUID;
BEGIN
    IF NEW.bank_connection_id IS NOT NULL THEN
        SELECT user_id
        INTO connection_user_id
        FROM bank_connection
        WHERE id = NEW.bank_connection_id;

        IF connection_user_id IS DISTINCT FROM NEW.user_id THEN
            RAISE EXCEPTION 'Financial account and bank connection must belong to the same user'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_sync_job_authorization()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    authorization_connection_id UUID;
    authorization_status VARCHAR(20);
    authorization_expiry TIMESTAMPTZ;
    authorization_revoked_at TIMESTAMPTZ;
    authorization_scopes VARCHAR(40)[];
    connection_status VARCHAR(20);
BEGIN
    SELECT bank_connection_id, status, expires_at, revoked_at, granted_scopes
    INTO authorization_connection_id, authorization_status, authorization_expiry,
         authorization_revoked_at, authorization_scopes
    FROM bank_connection_authorization
    WHERE id = NEW.authorization_id;

    SELECT status
    INTO connection_status
    FROM bank_connection
    WHERE id = NEW.bank_connection_id;

    IF authorization_connection_id IS DISTINCT FROM NEW.bank_connection_id THEN
        RAISE EXCEPTION 'Synchronization authorization must belong to the same bank connection'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('QUEUED', 'RUNNING') THEN
        IF connection_status IS DISTINCT FROM 'ACTIVE'
           OR authorization_status IS DISTINCT FROM 'ACTIVE'
           OR authorization_revoked_at IS NOT NULL
           OR authorization_expiry <= CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION 'Synchronization requires an active, non-expired bank authorization'
                USING ERRCODE = '23514';
        END IF;

        IF NOT authorization_scopes @> ARRAY[
            'ACCOUNTS_READ', 'BALANCES_READ', 'TRANSACTIONS_READ'
        ]::VARCHAR(40)[] THEN
            RAISE EXCEPTION 'Synchronization authorization does not grant every required read scope'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_balance_snapshot_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    account_currency CHAR(3);
    account_connection_id UUID;
    job_connection_id UUID;
BEGIN
    SELECT currency, bank_connection_id
    INTO account_currency, account_connection_id
    FROM financial_account
    WHERE id = NEW.financial_account_id;

    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'Balance snapshot currency must match the financial account currency'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.sync_job_id IS NOT NULL THEN
        SELECT bank_connection_id
        INTO job_connection_id
        FROM sync_job
        WHERE id = NEW.sync_job_id;

        IF account_connection_id IS NULL OR account_connection_id IS DISTINCT FROM job_connection_id THEN
            RAISE EXCEPTION 'Balance snapshot account must belong to the synchronized connection'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bank_connection_set_updated_at
    BEFORE UPDATE ON bank_connection
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_bank_authorization_validate
    BEFORE INSERT OR UPDATE ON bank_connection_authorization
    FOR EACH ROW EXECUTE FUNCTION validate_bank_authorization_state();

CREATE TRIGGER trg_financial_account_validate_owner
    BEFORE INSERT OR UPDATE OF user_id, bank_connection_id ON financial_account
    FOR EACH ROW EXECUTE FUNCTION validate_financial_account_owner();

CREATE TRIGGER trg_financial_account_set_updated_at
    BEFORE UPDATE ON financial_account
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_sync_job_validate_authorization
    BEFORE INSERT OR UPDATE OF bank_connection_id, authorization_id, status ON sync_job
    FOR EACH ROW EXECUTE FUNCTION validate_sync_job_authorization();

CREATE TRIGGER trg_balance_snapshot_validate_scope
    BEFORE INSERT OR UPDATE ON balance_snapshot
    FOR EACH ROW EXECUTE FUNCTION validate_balance_snapshot_scope();
