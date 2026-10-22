CREATE TABLE forecast_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    currency CHAR(3) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    forecast_month DATE NOT NULL,
    local_cutoff_date DATE NOT NULL,
    snapshot_cutoff TIMESTAMPTZ NOT NULL,
    starting_balance NUMERIC(19, 4) NOT NULL,
    deterministic_remaining_income NUMERIC(19, 4) NOT NULL,
    deterministic_remaining_expense NUMERIC(19, 4) NOT NULL,
    deterministic_remaining_net_flow NUMERIC(19, 4) NOT NULL,
    deterministic_predicted_end_balance NUMERIC(19, 4) NOT NULL,
    ml_status VARCHAR(40) NOT NULL,
    ml_predicted_residual NUMERIC(19, 4),
    final_predicted_end_balance NUMERIC(19, 4) NOT NULL,
    deterministic_method VARCHAR(100) NOT NULL,
    deterministic_method_version VARCHAR(40) NOT NULL,
    model_id VARCHAR(120),
    model_version VARCHAR(60),
    feature_contract_version VARCHAR(100),
    experimental BOOLEAN NOT NULL,
    synthetic_training_data BOOLEAN NOT NULL,
    guaranteed BOOLEAN NOT NULL,
    canonical_input_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_forecast_result_currency CHECK (
        currency = UPPER(currency) AND currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_forecast_result_timezone CHECK (BTRIM(time_zone) <> ''),
    CONSTRAINT ck_forecast_result_month CHECK (
        forecast_month = DATE_TRUNC('month', forecast_month)::DATE
        AND local_cutoff_date >= forecast_month
        AND local_cutoff_date < forecast_month + INTERVAL '1 month'
    ),
    CONSTRAINT ck_forecast_result_deterministic CHECK (
        deterministic_remaining_income >= 0
        AND deterministic_remaining_expense >= 0
        AND deterministic_remaining_net_flow =
            deterministic_remaining_income - deterministic_remaining_expense
        AND deterministic_predicted_end_balance =
            starting_balance + deterministic_remaining_net_flow
    ),
    CONSTRAINT ck_forecast_result_ml_status CHECK (
        ml_status IN ('APPLIED', 'UNSUPPORTED_CURRENCY', 'INSUFFICIENT_HISTORY',
            'SERVICE_UNAVAILABLE', 'INVALID_RESPONSE', 'MODEL_VERSION_MISMATCH')
    ),
    CONSTRAINT ck_forecast_result_ml_metadata CHECK (
        (ml_status = 'APPLIED'
            AND ml_predicted_residual IS NOT NULL
            AND model_id IS NOT NULL AND BTRIM(model_id) <> ''
            AND model_version IS NOT NULL AND BTRIM(model_version) <> ''
            AND feature_contract_version IS NOT NULL AND BTRIM(feature_contract_version) <> ''
            AND experimental = TRUE AND synthetic_training_data = TRUE AND guaranteed = FALSE
            AND final_predicted_end_balance =
                deterministic_predicted_end_balance + ml_predicted_residual)
        OR
        (ml_status <> 'APPLIED'
            AND ml_predicted_residual IS NULL
            AND model_id IS NULL AND model_version IS NULL AND feature_contract_version IS NULL
            AND experimental = FALSE AND synthetic_training_data = FALSE AND guaranteed = FALSE
            AND final_predicted_end_balance = deterministic_predicted_end_balance)
    ),
    CONSTRAINT ck_forecast_result_fingerprint CHECK (
        canonical_input_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT uk_forecast_result_idempotency UNIQUE (
        user_id, currency, canonical_input_fingerprint, ml_status
    )
);

CREATE INDEX idx_forecast_result_user_currency_created
    ON forecast_result(user_id, currency, created_at DESC, id DESC);

CREATE FUNCTION guard_forecast_result_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'Forecast results are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_forecast_result_immutable
    BEFORE UPDATE OR DELETE ON forecast_result
    FOR EACH ROW EXECUTE FUNCTION guard_forecast_result_immutability();
