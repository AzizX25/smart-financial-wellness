ALTER TABLE bank_customer
    ADD COLUMN username VARCHAR(80),
    ADD COLUMN password_hash VARCHAR(100);

UPDATE bank_customer
SET username = CASE external_customer_id
        WHEN 'MOCK-CUST-001' THEN 'amina.demo'
        WHEN 'MOCK-CUST-002' THEN 'youssef.demo'
    END,
    password_hash = '$2a$12$iH0ORIlDjTg4Knk2AIIpneDiWATEjHj8RcHEhF.PHxJs4aoIJ8VT.';

ALTER TABLE bank_customer
    ALTER COLUMN username SET NOT NULL,
    ALTER COLUMN password_hash SET NOT NULL,
    ADD CONSTRAINT uk_bank_customer_username UNIQUE (username),
    ADD CONSTRAINT ck_bank_customer_username CHECK (
        BTRIM(username) <> '' AND username = LOWER(username)
    ),
    ADD CONSTRAINT ck_bank_customer_password_hash CHECK (
        password_hash ~ '^\$2[aby]\$[0-9]{2}\$.{53}$'
    );
