CREATE TABLE source_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    file_type VARCHAR(20) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_reference VARCHAR(500) NOT NULL,
    file_metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_source_file_user_hash UNIQUE (user_id, sha256),
    CONSTRAINT ck_source_file_type CHECK (file_type IN ('CSV', 'XLSX', 'XLS')),
    CONSTRAINT ck_source_file_name CHECK (BTRIM(original_filename) <> ''),
    CONSTRAINT ck_source_file_mime_type CHECK (BTRIM(mime_type) <> ''),
    CONSTRAINT ck_source_file_size CHECK (size_bytes > 0),
    CONSTRAINT ck_source_file_hash CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_file_storage_reference CHECK (BTRIM(storage_reference) <> ''),
    CONSTRAINT ck_source_file_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE TABLE import_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    financial_account_id UUID NOT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    source_file_id UUID NOT NULL REFERENCES source_file(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED',
    row_count INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    safe_error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_import_job_status CHECK (
        status IN ('UPLOADED', 'MAPPING_REQUIRED', 'READY', 'IMPORTING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_import_job_counts CHECK (
        row_count >= 0 AND imported_count >= 0 AND duplicate_count >= 0 AND rejected_count >= 0
    ),
    CONSTRAINT ck_import_job_dates CHECK (
        (started_at IS NULL OR started_at >= created_at) AND
        (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at))
    ),
    CONSTRAINT ck_import_job_running_started CHECK (
        status NOT IN ('IMPORTING', 'COMPLETED', 'FAILED') OR started_at IS NOT NULL
    ),
    CONSTRAINT ck_import_job_terminal_completed CHECK (
        status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') OR completed_at IS NOT NULL
    )
);

CREATE TABLE import_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_job_id UUID NOT NULL UNIQUE REFERENCES import_job(id) ON DELETE CASCADE,
    detection_method VARCHAR(30) NOT NULL,
    model_name VARCHAR(100),
    model_version VARCHAR(100),
    overall_confidence NUMERIC(5, 4),
    user_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_import_mapping_method CHECK (detection_method IN ('RULES', 'ML', 'LLM', 'USER')),
    CONSTRAINT ck_import_mapping_model CHECK (
        (detection_method IN ('ML', 'LLM') AND model_name IS NOT NULL AND model_version IS NOT NULL) OR
        (detection_method IN ('RULES', 'USER'))
    ),
    CONSTRAINT ck_import_mapping_confidence CHECK (
        overall_confidence IS NULL OR overall_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_import_mapping_confirmation CHECK (
        (user_confirmed = FALSE AND confirmed_at IS NULL) OR
        (user_confirmed = TRUE AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE column_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_mapping_id UUID NOT NULL REFERENCES import_mapping(id) ON DELETE CASCADE,
    source_column VARCHAR(255) NOT NULL,
    canonical_field VARCHAR(80) NOT NULL,
    detected_format VARCHAR(100),
    confidence NUMERIC(5, 4),
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_column_mapping_source UNIQUE (import_mapping_id, source_column),
    CONSTRAINT ck_column_mapping_source CHECK (BTRIM(source_column) <> ''),
    CONSTRAINT ck_column_mapping_field CHECK (BTRIM(canonical_field) <> ''),
    CONSTRAINT ck_column_mapping_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);

CREATE TABLE rejected_import_row (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_job_id UUID NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    source_row_number INTEGER NOT NULL,
    error_code VARCHAR(100) NOT NULL,
    safe_error_message VARCHAR(500),
    masked_row_data JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rejected_import_row UNIQUE (import_job_id, source_row_number),
    CONSTRAINT ck_rejected_import_row_number CHECK (source_row_number > 0),
    CONSTRAINT ck_rejected_import_error_code CHECK (BTRIM(error_code) <> '')
);

CREATE INDEX idx_source_file_user_date ON source_file(user_id, created_at DESC);
CREATE INDEX idx_import_job_user_date ON import_job(user_id, created_at DESC);
CREATE INDEX idx_import_job_account_date ON import_job(financial_account_id, created_at DESC);
CREATE INDEX idx_rejected_import_row_job ON rejected_import_row(import_job_id, source_row_number);

CREATE FUNCTION validate_import_job_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    account_user_id UUID;
    file_user_id UUID;
BEGIN
    SELECT user_id INTO account_user_id
    FROM financial_account
    WHERE id = NEW.financial_account_id;

    SELECT user_id INTO file_user_id
    FROM source_file
    WHERE id = NEW.source_file_id;

    IF account_user_id IS DISTINCT FROM NEW.user_id OR file_user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'Import job, financial account and source file must belong to the same user'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_import_job_validate_scope
    BEFORE INSERT OR UPDATE OF user_id, financial_account_id, source_file_id ON import_job
    FOR EACH ROW EXECUTE FUNCTION validate_import_job_scope();

CREATE TRIGGER trg_import_job_set_updated_at
    BEFORE UPDATE ON import_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();

CREATE TRIGGER trg_import_mapping_set_updated_at
    BEFORE UPDATE ON import_mapping
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
