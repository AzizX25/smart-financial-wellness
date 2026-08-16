CREATE TABLE bank_administrator (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_administrator_username UNIQUE (username),
    CONSTRAINT ck_bank_administrator_username CHECK (
        BTRIM(username) <> '' AND username = LOWER(username)
    ),
    CONSTRAINT ck_bank_administrator_password_hash CHECK (
        password_hash ~ '^\$2[aby]\$[0-9]{2}\$.{53}$'
    ),
    CONSTRAINT ck_bank_administrator_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

INSERT INTO bank_administrator (id, username, password_hash, status)
VALUES (
    '30000000-0000-0000-0000-000000000001',
    'admin.demo',
    '$2a$12$bkgxm7e.F88JeJw6RccMl.xerDqaIOVO81Vq/C3hD08QRm19K2lH.',
    'ACTIVE'
);

