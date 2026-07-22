CREATE TABLE user_consent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    consent_type VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED',
    policy_version VARCHAR(40) NOT NULL,
    granted_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_consent UNIQUE (user_id, consent_type),
    CONSTRAINT ck_user_consent_type CHECK (BTRIM(consent_type) <> ''),
    CONSTRAINT ck_user_consent_policy CHECK (BTRIM(policy_version) <> ''),
    CONSTRAINT ck_user_consent_status CHECK (status IN ('NOT_REQUESTED', 'GRANTED', 'WITHDRAWN')),
    CONSTRAINT ck_user_consent_state CHECK (
        (status = 'NOT_REQUESTED' AND granted_at IS NULL AND withdrawn_at IS NULL) OR
        (status = 'GRANTED' AND granted_at IS NOT NULL AND withdrawn_at IS NULL) OR
        (status = 'WITHDRAWN' AND granted_at IS NOT NULL AND withdrawn_at IS NOT NULL
            AND withdrawn_at >= granted_at)
    )
);

CREATE TABLE consent_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_consent_id UUID NOT NULL REFERENCES user_consent(id) ON DELETE RESTRICT,
    event_type VARCHAR(20) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_consent_event_type CHECK (event_type IN ('GRANTED', 'WITHDRAWN', 'RECONFIRMED')),
    CONSTRAINT ck_consent_event_policy CHECK (BTRIM(policy_version) <> '')
);

CREATE TABLE audit_log_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type VARCHAR(20) NOT NULL,
    actor_id UUID,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID,
    outcome VARCHAR(20) NOT NULL,
    change_summary JSONB,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN', 'SERVICE')),
    CONSTRAINT ck_audit_log_action CHECK (BTRIM(action) <> ''),
    CONSTRAINT ck_audit_log_resource_type CHECK (BTRIM(resource_type) <> ''),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'DENIED'))
);

CREATE TABLE personal_data_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    request_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_personal_data_request_type CHECK (
        request_type IN ('ACCESS', 'EXPORT', 'RECTIFICATION', 'DELETION')
    ),
    CONSTRAINT ck_personal_data_request_status CHECK (
        status IN ('REQUESTED', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_personal_data_request_dates CHECK (
        (confirmed_at IS NULL OR confirmed_at >= requested_at) AND
        (completed_at IS NULL OR completed_at >= requested_at)
    ),
    CONSTRAINT ck_personal_data_request_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL) OR
        (status <> 'FAILED' AND failure_reason IS NULL)
    )
);

CREATE TABLE personal_data_export (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    personal_data_request_id UUID NOT NULL UNIQUE
        REFERENCES personal_data_request(id) ON DELETE RESTRICT,
    export_format VARCHAR(20) NOT NULL,
    file_reference VARCHAR(500) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    downloaded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_personal_data_export_format CHECK (export_format IN ('JSON', 'CSV', 'ZIP')),
    CONSTRAINT ck_personal_data_export_file_reference CHECK (BTRIM(file_reference) <> ''),
    CONSTRAINT ck_personal_data_export_hash CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_personal_data_export_dates CHECK (
        expires_at > generated_at AND
        (downloaded_at IS NULL OR downloaded_at >= generated_at)
    )
);

CREATE INDEX idx_consent_event_consent_date
    ON consent_event(user_consent_id, occurred_at DESC);
CREATE INDEX idx_audit_log_actor_date
    ON audit_log_entry(actor_id, occurred_at DESC);
CREATE INDEX idx_audit_log_resource_date
    ON audit_log_entry(resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_log_correlation
    ON audit_log_entry(correlation_id);
CREATE INDEX idx_personal_data_request_user_date
    ON personal_data_request(user_id, requested_at DESC);
CREATE INDEX idx_personal_data_request_status
    ON personal_data_request(status, requested_at);

CREATE FUNCTION reject_append_only_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only and cannot be %', TG_TABLE_NAME, LOWER(TG_OP)
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION validate_consent_event_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    current_status VARCHAR(20);
    current_policy VARCHAR(40);
BEGIN
    SELECT status, policy_version
    INTO current_status, current_policy
    FROM user_consent
    WHERE id = NEW.user_consent_id;

    IF NEW.policy_version IS DISTINCT FROM current_policy THEN
        RAISE EXCEPTION 'Consent event policy version must match the consent state'
            USING ERRCODE = '23514';
    END IF;

    IF (NEW.event_type IN ('GRANTED', 'RECONFIRMED') AND current_status <> 'GRANTED')
       OR (NEW.event_type = 'WITHDRAWN' AND current_status <> 'WITHDRAWN') THEN
        RAISE EXCEPTION 'Consent event does not match the current consent state'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_personal_data_export_request()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    linked_request_type VARCHAR(30);
BEGIN
    SELECT request_type
    INTO linked_request_type
    FROM personal_data_request
    WHERE id = NEW.personal_data_request_id;

    IF linked_request_type IS DISTINCT FROM 'EXPORT' THEN
        RAISE EXCEPTION 'A personal data export requires an EXPORT request'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_consent_set_updated_at
    BEFORE UPDATE ON user_consent
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_consent_event_validate
    BEFORE INSERT ON consent_event
    FOR EACH ROW EXECUTE FUNCTION validate_consent_event_state();

CREATE TRIGGER trg_consent_event_append_only
    BEFORE UPDATE OR DELETE ON consent_event
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_audit_log_entry_append_only
    BEFORE UPDATE OR DELETE ON audit_log_entry
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_personal_data_request_set_updated_at
    BEFORE UPDATE ON personal_data_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_personal_data_export_validate
    BEFORE INSERT OR UPDATE ON personal_data_export
    FOR EACH ROW EXECUTE FUNCTION validate_personal_data_export_request();
