ALTER TABLE bank_connection
    ADD COLUMN disconnected_at TIMESTAMPTZ;

UPDATE bank_connection
SET disconnected_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE status = 'DISCONNECTED';

ALTER TABLE bank_connection
    ADD CONSTRAINT ck_bank_connection_disconnected_at CHECK (
        (status = 'DISCONNECTED' AND disconnected_at IS NOT NULL) OR
        (status <> 'DISCONNECTED' AND disconnected_at IS NULL)
    );

ALTER TABLE bank_connection_authorization
    ADD COLUMN provider_access_token TEXT,
    ADD CONSTRAINT ck_bank_authorization_provider_token_state CHECK (
        status = 'ACTIVE' OR provider_access_token IS NULL
    );

CREATE UNIQUE INDEX uk_bank_connection_current_mock_bank_user
    ON bank_connection(user_id)
    WHERE provider_code = 'MOCK_BANK'
      AND status IN ('PENDING', 'ACTIVE', 'ERROR');

CREATE UNIQUE INDEX uk_bank_connection_current_mock_bank_customer
    ON bank_connection(external_customer_id)
    WHERE provider_code = 'MOCK_BANK'
      AND external_customer_id IS NOT NULL
      AND status IN ('PENDING', 'ACTIVE', 'ERROR');
