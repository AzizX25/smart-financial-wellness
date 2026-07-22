CREATE TABLE authentication_identity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE RESTRICT,
    identity_type VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    normalized_identifier VARCHAR(320) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_authentication_identity_identifier UNIQUE (identity_type, normalized_identifier),
    CONSTRAINT ck_authentication_identity_type CHECK (identity_type = 'EMAIL'),
    CONSTRAINT ck_authentication_identity_normalized CHECK (
        normalized_identifier = LOWER(BTRIM(normalized_identifier)) AND
        normalized_identifier ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
    ),
    CONSTRAINT ck_authentication_identity_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')
    ),
    CONSTRAINT ck_authentication_identity_verification CHECK (
        status <> 'ACTIVE' OR verified_at IS NOT NULL
    )
);

CREATE TABLE password_credential (
    authentication_identity_id UUID PRIMARY KEY
        REFERENCES authentication_identity(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    password_algorithm VARCHAR(60) NOT NULL,
    password_parameters JSONB NOT NULL DEFAULT '{}'::JSONB,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_password_credential_hash CHECK (BTRIM(password_hash) <> ''),
    CONSTRAINT ck_password_credential_algorithm CHECK (BTRIM(password_algorithm) <> ''),
    CONSTRAINT ck_password_credential_changed_at CHECK (password_changed_at >= created_at)
);

CREATE TABLE refresh_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authentication_identity_id UUID NOT NULL
        REFERENCES authentication_identity(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    replaced_by_session_id UUID REFERENCES refresh_session(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(100),
    reuse_detected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_refresh_session_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_session_hash CHECK (BTRIM(token_hash) <> ''),
    CONSTRAINT ck_refresh_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_session_last_used CHECK (last_used_at IS NULL OR last_used_at >= created_at),
    CONSTRAINT ck_refresh_session_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL) OR
        (revoked_at IS NOT NULL AND revoked_at >= created_at AND revocation_reason IS NOT NULL)
    ),
    CONSTRAINT ck_refresh_session_reuse CHECK (
        reuse_detected_at IS NULL OR
        (last_used_at IS NOT NULL AND reuse_detected_at >= last_used_at)
    ),
    CONSTRAINT ck_refresh_session_replacement CHECK (replaced_by_session_id IS NULL OR replaced_by_session_id <> id)
);

CREATE TABLE account_action_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authentication_identity_id UUID NOT NULL
        REFERENCES authentication_identity(id) ON DELETE CASCADE,
    action_type VARCHAR(30) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_account_action_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_action_token_type CHECK (
        action_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')
    ),
    CONSTRAINT ck_account_action_token_hash CHECK (BTRIM(token_hash) <> ''),
    CONSTRAINT ck_account_action_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_account_action_token_use CHECK (used_at IS NULL OR used_at >= created_at),
    CONSTRAINT ck_account_action_token_invalidation CHECK (
        invalidated_at IS NULL OR invalidated_at >= created_at
    ),
    CONSTRAINT ck_account_action_token_terminal_state CHECK (
        NUM_NONNULLS(used_at, invalidated_at) <= 1
    )
);

CREATE INDEX idx_authentication_identity_user_status
    ON authentication_identity(user_id, status);
CREATE INDEX idx_refresh_session_identity_expiry
    ON refresh_session(authentication_identity_id, expires_at);
CREATE INDEX idx_refresh_session_family
    ON refresh_session(family_id, created_at);
CREATE INDEX idx_account_action_token_identity_type
    ON account_action_token(authentication_identity_id, action_type, expires_at);

CREATE FUNCTION validate_refresh_session_rotation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    successor_identity_id UUID;
    successor_family_id UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.token_hash IS DISTINCT FROM NEW.token_hash THEN
        RAISE EXCEPTION 'A refresh token hash is immutable' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.last_used_at IS NOT NULL
       AND NEW.last_used_at IS DISTINCT FROM OLD.last_used_at THEN
        RAISE EXCEPTION 'A refresh session can only be consumed once' USING ERRCODE = '23514';
    END IF;

    IF NEW.replaced_by_session_id IS NOT NULL THEN
        SELECT authentication_identity_id, family_id
        INTO successor_identity_id, successor_family_id
        FROM refresh_session
        WHERE id = NEW.replaced_by_session_id;

        IF successor_identity_id IS DISTINCT FROM NEW.authentication_identity_id
           OR successor_family_id IS DISTINCT FROM NEW.family_id THEN
            RAISE EXCEPTION 'Refresh token rotation must remain in the same identity and family'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.last_used_at IS NULL OR NEW.revoked_at IS NULL THEN
            RAISE EXCEPTION 'A rotated refresh session must be consumed and revoked'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_account_action_token()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF OLD.token_hash IS DISTINCT FROM NEW.token_hash
       OR OLD.action_type IS DISTINCT FROM NEW.action_type
       OR OLD.authentication_identity_id IS DISTINCT FROM NEW.authentication_identity_id THEN
        RAISE EXCEPTION 'Account action token identity and hash are immutable' USING ERRCODE = '23514';
    END IF;

    IF OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at THEN
        RAISE EXCEPTION 'An account action token can only be used once' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authentication_identity_set_updated_at
    BEFORE UPDATE ON authentication_identity
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_password_credential_set_updated_at
    BEFORE UPDATE ON password_credential
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_refresh_session_rotation
    BEFORE INSERT OR UPDATE ON refresh_session
    FOR EACH ROW EXECUTE FUNCTION validate_refresh_session_rotation();

CREATE TRIGGER trg_refresh_session_set_updated_at
    BEFORE UPDATE ON refresh_session
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_account_action_token_guard
    BEFORE UPDATE ON account_action_token
    FOR EACH ROW EXECUTE FUNCTION guard_account_action_token();
