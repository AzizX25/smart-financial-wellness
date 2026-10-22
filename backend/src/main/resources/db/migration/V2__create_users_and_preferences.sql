CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_display_name CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT ck_app_user_first_name CHECK (first_name IS NULL OR BTRIM(first_name) <> ''),
    CONSTRAINT ck_app_user_last_name CHECK (last_name IS NULL OR BTRIM(last_name) <> ''),
    CONSTRAINT ck_app_user_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED')
    )
);

CREATE TABLE interface_preference (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE RESTRICT,
    language_tag VARCHAR(35) NOT NULL DEFAULT 'fr-TN',
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Africa/Tunis',
    theme VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    text_size VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    high_contrast BOOLEAN NOT NULL DEFAULT FALSE,
    reduced_motion BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_interface_preference_language CHECK (BTRIM(language_tag) <> ''),
    CONSTRAINT ck_interface_preference_time_zone CHECK (BTRIM(time_zone) <> ''),
    CONSTRAINT ck_interface_preference_theme CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM')),
    CONSTRAINT ck_interface_preference_text_size CHECK (text_size IN ('NORMAL', 'LARGE', 'EXTRA_LARGE'))
);

CREATE TABLE financial_preference (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE RESTRICT,
    primary_currency CHAR(3) NOT NULL DEFAULT 'TND',
    month_start_day SMALLINT NOT NULL DEFAULT 1,
    low_balance_threshold NUMERIC(19, 4),
    hide_home_amounts BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_financial_preference_currency CHECK (
        primary_currency = UPPER(primary_currency) AND primary_currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_financial_preference_month_start CHECK (month_start_day BETWEEN 1 AND 28),
    CONSTRAINT ck_financial_preference_low_balance CHECK (
        low_balance_threshold IS NULL OR low_balance_threshold >= 0
    )
);

CREATE TABLE notification_preference (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    event_type VARCHAR(60) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_priority VARCHAR(20) NOT NULL DEFAULT 'LOW',
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_notification_preference UNIQUE (user_id, event_type, channel),
    CONSTRAINT ck_notification_preference_event CHECK (BTRIM(event_type) <> ''),
    CONSTRAINT ck_notification_preference_channel CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH')),
    CONSTRAINT ck_notification_preference_priority CHECK (
        minimum_priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_notification_preference_quiet_hours CHECK (
        (quiet_hours_start IS NULL AND quiet_hours_end IS NULL) OR
        (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL)
    )
);

CREATE INDEX idx_app_user_status ON app_user(status);
CREATE INDEX idx_notification_preference_user ON notification_preference(user_id);

CREATE FUNCTION set_updated_at_and_version()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_app_user_set_updated_at
    BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_interface_preference_set_updated_at
    BEFORE UPDATE ON interface_preference
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_financial_preference_set_updated_at
    BEFORE UPDATE ON financial_preference
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_notification_preference_set_updated_at
    BEFORE UPDATE ON notification_preference
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
