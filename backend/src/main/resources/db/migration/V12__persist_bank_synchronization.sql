ALTER TABLE financial_account
    ADD COLUMN provider_code VARCHAR(60),
    ADD COLUMN available_balance NUMERIC(19, 4),
    ADD COLUMN provider_account_status VARCHAR(40),
    ADD COLUMN first_synchronized_at TIMESTAMPTZ,
    ADD COLUMN last_synchronized_at TIMESTAMPTZ;

UPDATE financial_account account
SET provider_code = connection.provider_code
FROM bank_connection connection
WHERE account.bank_connection_id = connection.id
  AND account.account_origin = 'BANK_CONNECTION';

ALTER TABLE financial_account
    ADD CONSTRAINT ck_financial_account_provider_code CHECK (
        provider_code IS NULL OR BTRIM(provider_code) <> ''
    ),
    ADD CONSTRAINT ck_financial_account_provider_status CHECK (
        provider_account_status IS NULL OR BTRIM(provider_account_status) <> ''
    ),
    ADD CONSTRAINT ck_financial_account_sync_dates CHECK (
        (first_synchronized_at IS NULL AND last_synchronized_at IS NULL) OR
        (first_synchronized_at IS NOT NULL AND last_synchronized_at >= first_synchronized_at)
    ),
    ADD CONSTRAINT ck_financial_account_provider_identity CHECK (
        (account_origin = 'MANUAL' AND provider_code IS NULL) OR
        (account_origin = 'BANK_CONNECTION' AND provider_code IS NOT NULL)
    );

CREATE UNIQUE INDEX uk_financial_account_user_provider_external
    ON financial_account(user_id, provider_code, external_account_id)
    WHERE provider_code IS NOT NULL
      AND external_account_id IS NOT NULL
      AND first_synchronized_at IS NOT NULL;

ALTER TABLE bank_connection
    ADD COLUMN sync_status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN last_sync_attempted_at TIMESTAMPTZ,
    ADD COLUMN last_sync_failure_code VARCHAR(100),
    ADD CONSTRAINT ck_bank_connection_sync_status CHECK (
        sync_status IN ('IDLE', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    ADD CONSTRAINT ck_bank_connection_sync_failure CHECK (
        (sync_status = 'FAILED' AND last_sync_failure_code IS NOT NULL
            AND BTRIM(last_sync_failure_code) <> '') OR
        (sync_status <> 'FAILED' AND last_sync_failure_code IS NULL)
    ),
    ADD CONSTRAINT ck_bank_connection_sync_attempt CHECK (
        sync_status = 'IDLE' OR last_sync_attempted_at IS NOT NULL
    );

ALTER TABLE sync_job
    ADD COLUMN accounts_created INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN accounts_updated INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN transactions_created INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN transactions_updated INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_sync_job_entity_counts CHECK (
        accounts_created >= 0 AND accounts_updated >= 0
        AND transactions_created >= 0 AND transactions_updated >= 0
    );

ALTER TABLE transaction_record
    ADD COLUMN provider_status VARCHAR(40),
    ADD COLUMN first_imported_at TIMESTAMPTZ,
    ADD COLUMN last_synchronized_at TIMESTAMPTZ;

UPDATE transaction_record
SET provider_status = 'UNKNOWN',
    first_imported_at = created_at,
    last_synchronized_at = updated_at
WHERE source_type = 'BANK_SYNC';

ALTER TABLE transaction_record
    ADD CONSTRAINT ck_transaction_provider_status CHECK (
        provider_status IS NULL OR BTRIM(provider_status) <> ''
    ),
    ADD CONSTRAINT ck_transaction_sync_dates CHECK (
        (first_imported_at IS NULL AND last_synchronized_at IS NULL) OR
        (first_imported_at IS NOT NULL AND last_synchronized_at >= first_imported_at)
    ),
    ADD CONSTRAINT ck_transaction_bank_sync_metadata CHECK (
        (source_type = 'BANK_SYNC' AND provider_status IS NOT NULL
            AND first_imported_at IS NOT NULL AND last_synchronized_at IS NOT NULL) OR
        (source_type <> 'BANK_SYNC' AND provider_status IS NULL
            AND first_imported_at IS NULL AND last_synchronized_at IS NULL)
    );

CREATE INDEX idx_transaction_user_account_date
    ON transaction_record(user_id, financial_account_id, booking_date DESC, id DESC);
