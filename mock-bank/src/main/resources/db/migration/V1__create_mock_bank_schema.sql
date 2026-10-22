CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS mock_bank;

COMMENT ON SCHEMA mock_bank IS 'Synthetic mock-bank schema managed by Flyway';

CREATE TABLE bank_customer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_customer_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    country_code CHAR(2) NOT NULL DEFAULT 'TN',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_customer_external_customer_id UNIQUE (external_customer_id),
    CONSTRAINT ck_bank_customer_external_customer_id CHECK (BTRIM(external_customer_id) <> ''),
    CONSTRAINT ck_bank_customer_display_name CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT ck_bank_customer_country_code CHECK (
        country_code = UPPER(country_code) AND country_code ~ '^[A-Z]{2}$'
    ),
    CONSTRAINT ck_bank_customer_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE oauth_client (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_identifier VARCHAR(80) NOT NULL,
    client_name VARCHAR(160) NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_oauth_client_identifier UNIQUE (client_identifier),
    CONSTRAINT ck_oauth_client_identifier CHECK (BTRIM(client_identifier) <> ''),
    CONSTRAINT ck_oauth_client_name CHECK (BTRIM(client_name) <> ''),
    CONSTRAINT ck_oauth_client_redirect_uri CHECK (BTRIM(redirect_uri) <> '')
);

CREATE TABLE bank_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_customer_id UUID NOT NULL REFERENCES bank_customer(id) ON DELETE RESTRICT,
    external_account_id VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,
    current_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    available_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_account_external_account_id UNIQUE (external_account_id),
    CONSTRAINT uk_bank_account_customer_external UNIQUE (bank_customer_id, external_account_id),
    CONSTRAINT ck_bank_account_external_account_id CHECK (BTRIM(external_account_id) <> ''),
    CONSTRAINT ck_bank_account_display_name CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT ck_bank_account_type CHECK (
        account_type IN ('CHECKING', 'SAVINGS', 'CASH', 'OTHER')
    ),
    CONSTRAINT ck_bank_account_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_bank_account_balances CHECK (
        current_balance >= -999999999999999.9999 AND available_balance >= -999999999999999.9999
    ),
    CONSTRAINT ck_bank_account_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'CLOSED'))
);

CREATE TABLE bank_transaction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_account_id UUID NOT NULL REFERENCES bank_account(id) ON DELETE RESTRICT,
    external_transaction_id VARCHAR(120) NOT NULL,
    booking_date DATE NOT NULL,
    value_date DATE,
    amount NUMERIC(19, 4) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    currency CHAR(3) NOT NULL,
    description VARCHAR(500) NOT NULL,
    counterparty_name VARCHAR(160),
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_transaction_external UNIQUE (bank_account_id, external_transaction_id),
    CONSTRAINT ck_bank_transaction_external CHECK (BTRIM(external_transaction_id) <> ''),
    CONSTRAINT ck_bank_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_bank_transaction_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_bank_transaction_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_bank_transaction_description CHECK (BTRIM(description) <> ''),
    CONSTRAINT ck_bank_transaction_status CHECK (status IN ('POSTED', 'PENDING', 'REVERSED'))
);

CREATE TABLE bank_consent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_customer_id UUID NOT NULL REFERENCES bank_customer(id) ON DELETE RESTRICT,
    oauth_client_id UUID NOT NULL REFERENCES oauth_client(id) ON DELETE RESTRICT,
    granted_scopes TEXT[] NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    consented_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_consent_customer_client UNIQUE (bank_customer_id, oauth_client_id),
    CONSTRAINT ck_bank_consent_scopes CHECK (CARDINALITY(granted_scopes) > 0),
    CONSTRAINT ck_bank_consent_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_bank_consent_dates CHECK (expires_at > consented_at),
    CONSTRAINT ck_bank_consent_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL) OR
        (status <> 'ACTIVE' AND revoked_at IS NOT NULL)
    )
);

CREATE TABLE authorization_code (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_consent_id UUID NOT NULL REFERENCES bank_consent(id) ON DELETE RESTRICT,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_authorization_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_authorization_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_authorization_code_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_authorization_code_used CHECK (used_at IS NULL OR used_at >= created_at)
);

CREATE TABLE access_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_consent_id UUID NOT NULL REFERENCES bank_consent(id) ON DELETE RESTRICT,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_access_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_access_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_access_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_access_token_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_access_token_last_used CHECK (last_used_at IS NULL OR last_used_at >= created_at)
);

CREATE INDEX idx_bank_account_customer ON bank_account(bank_customer_id);
CREATE INDEX idx_bank_transaction_account_date ON bank_transaction(bank_account_id, booking_date DESC);
CREATE INDEX idx_bank_consent_customer_client ON bank_consent(bank_customer_id, oauth_client_id);
CREATE INDEX idx_authorization_code_consent ON authorization_code(bank_consent_id, created_at DESC);
CREATE INDEX idx_access_token_consent ON access_token(bank_consent_id, created_at DESC);