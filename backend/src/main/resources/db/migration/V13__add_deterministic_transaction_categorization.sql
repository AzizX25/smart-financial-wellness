ALTER TABLE category
    ADD COLUMN nature VARCHAR(10),
    ADD COLUMN icon_key VARCHAR(40),
    ADD COLUMN color VARCHAR(7);

UPDATE category
SET nature = CASE
        WHEN code IN ('INCOME_SALARY', 'INCOME_OTHER') THEN 'INCOME'
        WHEN code IN ('SAVINGS', 'TRANSFER_IN', 'TRANSFER_OUT') THEN 'TRANSFER'
        WHEN code = 'UNCATEGORIZED' THEN 'NEUTRAL'
        ELSE 'EXPENSE'
    END,
    label = CASE code
        WHEN 'UNCATEGORIZED' THEN 'Non catégorisé'
        WHEN 'INCOME_SALARY' THEN 'Salaire'
        WHEN 'INCOME_OTHER' THEN 'Revenus divers'
        WHEN 'HOUSING' THEN 'Logement'
        WHEN 'FOOD' THEN 'Alimentation et courses'
        WHEN 'TRANSPORT' THEN 'Transport'
        WHEN 'HEALTH' THEN 'Santé'
        WHEN 'SAVINGS' THEN 'Épargne'
        WHEN 'TRANSFER_IN' THEN 'Transferts entrants'
        WHEN 'TRANSFER_OUT' THEN 'Transferts sortants'
        ELSE label
    END,
    icon_key = CASE code
        WHEN 'UNCATEGORIZED' THEN 'help'
        WHEN 'INCOME_SALARY' THEN 'briefcase'
        WHEN 'INCOME_OTHER' THEN 'income'
        WHEN 'HOUSING' THEN 'home'
        WHEN 'FOOD' THEN 'basket'
        WHEN 'TRANSPORT' THEN 'transport'
        WHEN 'HEALTH' THEN 'health'
        WHEN 'SAVINGS' THEN 'savings'
        ELSE 'transfer'
    END,
    color = CASE
        WHEN direction = 'CREDIT' THEN '#2E8B68'
        WHEN direction = 'DEBIT' THEN '#D66A4A'
        ELSE '#64748B'
    END;

ALTER TABLE category
    ALTER COLUMN nature SET NOT NULL,
    ADD CONSTRAINT ck_category_nature CHECK (nature IN ('EXPENSE', 'INCOME', 'TRANSFER', 'NEUTRAL')),
    ADD CONSTRAINT ck_category_label_trimmed CHECK (label = BTRIM(label)),
    ADD CONSTRAINT ck_category_icon_key CHECK (
        icon_key IS NULL OR icon_key ~ '^[a-z0-9-]{1,40}$'
    ),
    ADD CONSTRAINT ck_category_color CHECK (
        color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$'
    ),
    ADD CONSTRAINT ck_category_nature_direction CHECK (
        (nature = 'EXPENSE' AND direction = 'DEBIT') OR
        (nature = 'INCOME' AND direction = 'CREDIT') OR
        (nature = 'TRANSFER' AND direction IN ('DEBIT', 'CREDIT', 'BOTH')) OR
        (nature = 'NEUTRAL' AND direction = 'BOTH')
    );

CREATE UNIQUE INDEX uk_category_user_label
    ON category(user_id, LOWER(label))
    WHERE user_id IS NOT NULL;

INSERT INTO category (id, code, label, taxonomy_version, direction, nature, icon_key, color) VALUES
    ('00000000-0000-0000-0000-000000000011', 'BILLS_SUBSCRIPTIONS', 'Factures et abonnements', 'v2', 'DEBIT', 'EXPENSE', 'bill', '#4B8FD8'),
    ('00000000-0000-0000-0000-000000000012', 'LEISURE', 'Loisirs', 'v2', 'DEBIT', 'EXPENSE', 'leisure', '#8B6CCF'),
    ('00000000-0000-0000-0000-000000000013', 'SHOPPING', 'Shopping', 'v2', 'DEBIT', 'EXPENSE', 'shopping', '#D66A8A'),
    ('00000000-0000-0000-0000-000000000014', 'EDUCATION', 'Éducation', 'v2', 'DEBIT', 'EXPENSE', 'education', '#7A67C7'),
    ('00000000-0000-0000-0000-000000000015', 'BANK_FEES', 'Frais bancaires', 'v2', 'DEBIT', 'EXPENSE', 'bank', '#B46B52'),
    ('00000000-0000-0000-0000-000000000016', 'TAXES', 'Impôts', 'v2', 'DEBIT', 'EXPENSE', 'tax', '#9A6B52'),
    ('00000000-0000-0000-0000-000000000017', 'REFUNDS', 'Remboursements', 'v2', 'CREDIT', 'INCOME', 'refund', '#2E8B68');

CREATE TABLE categorization_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL UNIQUE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    match_field VARCHAR(20) NOT NULL,
    match_type VARCHAR(10) NOT NULL,
    normalized_pattern VARCHAR(500) NOT NULL,
    direction VARCHAR(10),
    priority INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_categorization_rule_code CHECK (BTRIM(code) <> ''),
    CONSTRAINT ck_categorization_rule_field CHECK (match_field IN ('DESCRIPTION', 'COUNTERPARTY')),
    CONSTRAINT ck_categorization_rule_type CHECK (match_type IN ('EXACT', 'CONTAINS')),
    CONSTRAINT ck_categorization_rule_pattern CHECK (
        normalized_pattern = BTRIM(normalized_pattern) AND BTRIM(normalized_pattern) <> ''
    ),
    CONSTRAINT ck_categorization_rule_direction CHECK (
        direction IS NULL OR direction IN ('DEBIT', 'CREDIT')
    ),
    CONSTRAINT ck_categorization_rule_priority CHECK (priority BETWEEN 0 AND 1000)
);

CREATE INDEX idx_categorization_rule_active
    ON categorization_rule(active, match_type, priority DESC, code);

INSERT INTO categorization_rule
    (id, code, category_id, match_field, match_type, normalized_pattern, direction, priority) VALUES
    ('10000000-0000-0000-0000-000000000001', 'SALARY_DESCRIPTION', '00000000-0000-0000-0000-000000000002', 'DESCRIPTION', 'CONTAINS', 'salaire', 'CREDIT', 200),
    ('10000000-0000-0000-0000-000000000002', 'MONOPRIX_COUNTERPARTY', '00000000-0000-0000-0000-000000000005', 'COUNTERPARTY', 'EXACT', 'monoprix', 'DEBIT', 300),
    ('10000000-0000-0000-0000-000000000003', 'SUPERMARKET_DESCRIPTION', '00000000-0000-0000-0000-000000000005', 'DESCRIPTION', 'CONTAINS', 'courses au supermarche', 'DEBIT', 200),
    ('10000000-0000-0000-0000-000000000004', 'RENT_DESCRIPTION', '00000000-0000-0000-0000-000000000004', 'DESCRIPTION', 'CONTAINS', 'paiement loyer', 'DEBIT', 200),
    ('10000000-0000-0000-0000-000000000005', 'STEG_COUNTERPARTY', '00000000-0000-0000-0000-000000000011', 'COUNTERPARTY', 'EXACT', 'steg', 'DEBIT', 300),
    ('10000000-0000-0000-0000-000000000006', 'BANK_FEES_DESCRIPTION', '00000000-0000-0000-0000-000000000015', 'DESCRIPTION', 'CONTAINS', 'frais bancaires', 'DEBIT', 200),
    ('10000000-0000-0000-0000-000000000007', 'ONLINE_SHOPPING_DESCRIPTION', '00000000-0000-0000-0000-000000000013', 'DESCRIPTION', 'CONTAINS', 'shopping en ligne', 'DEBIT', 200),
    ('10000000-0000-0000-0000-000000000008', 'TRANSFER_TO_SAVINGS', '00000000-0000-0000-0000-000000000010', 'DESCRIPTION', 'EXACT', 'virement vers epargne', 'DEBIT', 400),
    ('10000000-0000-0000-0000-000000000009', 'TRANSFER_FROM_CURRENT', '00000000-0000-0000-0000-000000000009', 'DESCRIPTION', 'EXACT', 'virement depuis courant', 'CREDIT', 400),
    ('10000000-0000-0000-0000-000000000010', 'REFUND_DESCRIPTION', '00000000-0000-0000-0000-000000000017', 'DESCRIPTION', 'CONTAINS', 'remboursement', 'CREDIT', 200);

ALTER TABLE transaction_record
    ADD COLUMN matched_categorization_rule_id UUID REFERENCES categorization_rule(id) ON DELETE RESTRICT,
    ADD COLUMN categorized_at TIMESTAMPTZ;

CREATE TABLE transaction_category_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transaction_record(id) ON DELETE RESTRICT,
    previous_category_id UUID REFERENCES category(id) ON DELETE RESTRICT,
    new_category_id UUID REFERENCES category(id) ON DELETE RESTRICT,
    previous_source VARCHAR(30) NOT NULL,
    new_source VARCHAR(30) NOT NULL,
    matched_rule_id UUID REFERENCES categorization_rule(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_transaction_category_history_source CHECK (
        previous_source IN ('UNCATEGORIZED', 'RULES', 'USER')
        AND new_source IN ('UNCATEGORIZED', 'RULES', 'USER')
    ),
    CONSTRAINT ck_transaction_category_history_change CHECK (
        previous_category_id IS DISTINCT FROM new_category_id
        OR previous_source IS DISTINCT FROM new_source
    )
);

CREATE INDEX idx_transaction_category_history_transaction
    ON transaction_category_history(transaction_id, changed_at DESC);
CREATE INDEX idx_transaction_category_history_actor
    ON transaction_category_history(actor_user_id, changed_at DESC);
CREATE INDEX idx_transaction_user_category_date
    ON transaction_record(user_id, category_id, booking_date DESC);

CREATE FUNCTION validate_transaction_category_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    category_direction VARCHAR(10);
    rule_category_id UUID;
BEGIN
    SELECT direction INTO category_direction FROM category WHERE id = NEW.category_id;
    IF category_direction <> 'BOTH' AND category_direction IS DISTINCT FROM NEW.direction THEN
        RAISE EXCEPTION 'Transaction direction is incompatible with category direction'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.matched_categorization_rule_id IS NOT NULL THEN
        SELECT category_id INTO rule_category_id
        FROM categorization_rule
        WHERE id = NEW.matched_categorization_rule_id AND active;
        IF rule_category_id IS DISTINCT FROM NEW.category_id THEN
            RAISE EXCEPTION 'Matched categorization rule must be active and target the assigned category'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.category_source = 'UNCATEGORIZED' AND
       (NEW.category_id <> '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NOT NULL
        OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Uncategorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'RULES' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NULL
        OR NEW.matched_categorization_rule_id IS NULL
        OR NEW.categorized_at IS NULL) THEN
        RAISE EXCEPTION 'Rule-categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.category_source = 'USER' AND
       (NEW.category_id = '00000000-0000-0000-0000-000000000001'::UUID
        OR NEW.applied_category_prediction_id IS NOT NULL
        OR NEW.matched_categorization_rule_id IS NOT NULL
        OR NEW.categorized_at IS NULL) THEN
        RAISE EXCEPTION 'Manually categorized transaction state is inconsistent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_transaction_category_history_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
DECLARE
    transaction_user_id UUID;
    previous_category_user_id UUID;
    new_category_user_id UUID;
BEGIN
    SELECT user_id INTO transaction_user_id FROM transaction_record WHERE id = NEW.transaction_id;
    SELECT user_id INTO previous_category_user_id FROM category WHERE id = NEW.previous_category_id;
    SELECT user_id INTO new_category_user_id FROM category WHERE id = NEW.new_category_id;
    IF transaction_user_id IS DISTINCT FROM NEW.actor_user_id
       OR (previous_category_user_id IS NOT NULL AND previous_category_user_id IS DISTINCT FROM transaction_user_id)
       OR (new_category_user_id IS NOT NULL AND new_category_user_id IS DISTINCT FROM transaction_user_id) THEN
        RAISE EXCEPTION 'Categorization history must remain within one user scope' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_categorization_rule_set_updated_at
    BEFORE UPDATE ON categorization_rule
    FOR EACH ROW EXECUTE FUNCTION set_updated_at_and_version();
CREATE TRIGGER trg_transaction_category_assignment
    BEFORE INSERT OR UPDATE OF category_id, category_source, applied_category_prediction_id,
        matched_categorization_rule_id, categorized_at, direction ON transaction_record
    FOR EACH ROW EXECUTE FUNCTION validate_transaction_category_assignment();
CREATE TRIGGER trg_transaction_category_history_scope
    BEFORE INSERT ON transaction_category_history
    FOR EACH ROW EXECUTE FUNCTION validate_transaction_category_history_scope();
CREATE TRIGGER trg_transaction_category_history_append_only
    BEFORE UPDATE OR DELETE ON transaction_category_history
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
