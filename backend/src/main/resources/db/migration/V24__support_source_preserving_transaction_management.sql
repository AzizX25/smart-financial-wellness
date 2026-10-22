-- Local corrections/removal must retain import and bank identities for replay safety.
ALTER TABLE transaction_record DROP CONSTRAINT ck_transaction_manual_removal;
ALTER TABLE transaction_record ADD CONSTRAINT ck_transaction_removal_date
    CHECK (deleted_at IS NULL OR deleted_at >= created_at);
ALTER TABLE transaction_record ADD COLUMN local_override BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transaction_record ADD COLUMN source_details JSONB;
ALTER TABLE transaction_record ADD CONSTRAINT ck_transaction_source_details
    CHECK ((source_details IS NULL OR jsonb_typeof(source_details) = 'object')
           AND (NOT local_override OR source_details IS NOT NULL));

-- V23's link guards and irreversible tombstone protection remain in force for every source.
