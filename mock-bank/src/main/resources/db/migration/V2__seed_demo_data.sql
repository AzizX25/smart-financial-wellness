INSERT INTO bank_customer (id, external_customer_id, display_name, country_code, status, created_at) VALUES
    ('11111111-1111-1111-1111-111111111111', 'MOCK-CUST-001', 'Amina Ben Romdhane', 'TN', 'ACTIVE',
        TIMESTAMPTZ '2026-07-01 08:00:00+00'),
    ('22222222-2222-2222-2222-222222222222', 'MOCK-CUST-002', 'Youssef Trabelsi', 'TN', 'ACTIVE',
        TIMESTAMPTZ '2026-07-01 08:00:00+00');

INSERT INTO oauth_client (id, client_identifier, client_name, redirect_uri, enabled, created_at) VALUES
    ('33333333-3333-3333-3333-333333333333', 'sfwa-local-demo-client',
        'Smart Financial Wellness Demo Client', 'http://localhost:8080/mock-bank/callback', TRUE,
        TIMESTAMPTZ '2026-07-01 08:00:00+00');

INSERT INTO bank_account (
    id, bank_customer_id, external_account_id, display_name, account_type, currency,
    current_balance, available_balance, status, created_at
) VALUES
    ('44444444-4444-4444-4444-444444444401', '11111111-1111-1111-1111-111111111111', 'MOCK-ACC-001',
        'Compte courant principal', 'CHECKING', 'TND', 3626.2500, 3626.2500, 'ACTIVE',
        TIMESTAMPTZ '2026-07-01 08:05:00+00'),
    ('44444444-4444-4444-4444-444444444402', '11111111-1111-1111-1111-111111111111', 'MOCK-ACC-002',
        'Compte épargne', 'SAVINGS', 'TND', 8420.3000, 8420.3000, 'ACTIVE',
        TIMESTAMPTZ '2026-07-01 08:05:00+00'),
    ('44444444-4444-4444-4444-444444444403', '22222222-2222-2222-2222-222222222222', 'MOCK-ACC-003',
        'Compte courant secondaire', 'CHECKING', 'TND', 1950.1000, 1950.1000, 'ACTIVE',
        TIMESTAMPTZ '2026-07-01 08:05:00+00');

INSERT INTO bank_transaction (
    id, bank_account_id, external_transaction_id, booking_date, value_date, amount, direction,
    currency, description, counterparty_name, status, created_at
) VALUES
    ('55555555-5555-5555-5555-555555555501', '44444444-4444-4444-4444-444444444401', 'MOCK-TXN-001',
        DATE '2026-07-25', DATE '2026-07-25', 3200.0000, 'CREDIT', 'TND', 'Salaire juillet 2026',
        'Entreprise Atlas', 'POSTED', TIMESTAMPTZ '2026-07-25 09:00:00+00'),
    ('55555555-5555-5555-5555-555555555502', '44444444-4444-4444-4444-444444444401', 'MOCK-TXN-002',
        DATE '2026-07-26', DATE '2026-07-26', 86.4500, 'DEBIT', 'TND', 'Courses au supermarché',
        'Monoprix', 'POSTED', TIMESTAMPTZ '2026-07-26 12:10:00+00'),
    ('55555555-5555-5555-5555-555555555503', '44444444-4444-4444-4444-444444444401', 'MOCK-TXN-003',
        DATE '2026-07-27', DATE '2026-07-27', 127.3000, 'DEBIT', 'TND', 'Facture électricité',
        'STEG', 'POSTED', TIMESTAMPTZ '2026-07-27 08:30:00+00'),
    ('55555555-5555-5555-5555-555555555504', '44444444-4444-4444-4444-444444444401', 'MOCK-TXN-004',
        DATE '2026-07-28', DATE '2026-07-28', 300.0000, 'DEBIT', 'TND', 'Virement vers épargne',
        'Virement interne', 'POSTED', TIMESTAMPTZ '2026-07-28 10:00:00+00'),
    ('55555555-5555-5555-5555-555555555505', '44444444-4444-4444-4444-444444444402', 'MOCK-TXN-005',
        DATE '2026-07-28', DATE '2026-07-28', 300.0000, 'CREDIT', 'TND', 'Virement depuis courant',
        'Virement interne', 'POSTED', TIMESTAMPTZ '2026-07-28 10:00:00+00'),
    ('55555555-5555-5555-5555-555555555506', '44444444-4444-4444-4444-444444444401', 'MOCK-TXN-006',
        DATE '2026-07-29', DATE '2026-07-29', 60.0000, 'DEBIT', 'TND', 'Retrait DAB',
        'ATM Banque Démo', 'POSTED', TIMESTAMPTZ '2026-07-29 18:20:00+00'),
    ('55555555-5555-5555-5555-555555555507', '44444444-4444-4444-4444-444444444403', 'MOCK-TXN-007',
        DATE '2026-07-24', DATE '2026-07-24', 2100.0000, 'CREDIT', 'TND', 'Salaire juillet 2026',
        'Société Horizon', 'POSTED', TIMESTAMPTZ '2026-07-24 09:00:00+00'),
    ('55555555-5555-5555-5555-555555555508', '44444444-4444-4444-4444-444444444403', 'MOCK-TXN-008',
        DATE '2026-07-26', DATE '2026-07-26', 149.9000, 'DEBIT', 'TND', 'Shopping en ligne',
        'Boutique Démo', 'POSTED', TIMESTAMPTZ '2026-07-26 20:00:00+00');