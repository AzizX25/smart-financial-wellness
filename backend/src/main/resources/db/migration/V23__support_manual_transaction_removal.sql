-- Retain append-only categorization evidence while removing manual rows from active analytics.
ALTER TABLE transaction_record ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE transaction_record ADD CONSTRAINT ck_transaction_manual_removal
    CHECK (deleted_at IS NULL OR (source_type = 'MANUAL' AND deleted_at >= created_at));

CREATE FUNCTION protect_manual_transaction_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'Removed transactions cannot be changed or restored' USING ERRCODE = '23514';
    END IF;
    IF NEW.deleted_at IS NOT NULL AND (
        EXISTS (SELECT 1 FROM goal_contribution WHERE transaction_id = NEW.id)
        OR EXISTS (SELECT 1 FROM internal_transfer
                   WHERE outgoing_transaction_id = NEW.id OR incoming_transaction_id = NEW.id)
    ) THEN
        RAISE EXCEPTION 'Linked transactions cannot be removed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transaction_protect_removal
    BEFORE UPDATE ON transaction_record
    FOR EACH ROW EXECUTE FUNCTION protect_manual_transaction_removal();

-- Tombstones cannot become new sources for actual goal progress or paired transfers.
CREATE FUNCTION protect_links_to_removed_transactions()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = smart_financial_wellness, pg_temp
AS $$
BEGIN
    IF TG_TABLE_NAME = 'goal_contribution' THEN
        IF NEW.transaction_id IS NOT NULL THEN
            PERFORM id FROM transaction_record
                WHERE id = NEW.transaction_id AND deleted_at IS NULL FOR KEY SHARE;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'A contribution requires an active transaction' USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSE
        PERFORM id FROM transaction_record
            WHERE id = NEW.outgoing_transaction_id AND deleted_at IS NULL FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A transfer requires active transactions' USING ERRCODE = '23514';
        END IF;
        PERFORM id FROM transaction_record
            WHERE id = NEW.incoming_transaction_id AND deleted_at IS NULL FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A transfer requires active transactions' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_goal_contribution_protect_removed_transaction
    BEFORE INSERT OR UPDATE OF transaction_id ON goal_contribution
    FOR EACH ROW EXECUTE FUNCTION protect_links_to_removed_transactions();
CREATE TRIGGER trg_internal_transfer_protect_removed_transaction
    BEFORE INSERT OR UPDATE OF outgoing_transaction_id, incoming_transaction_id ON internal_transfer
    FOR EACH ROW EXECUTE FUNCTION protect_links_to_removed_transactions();
