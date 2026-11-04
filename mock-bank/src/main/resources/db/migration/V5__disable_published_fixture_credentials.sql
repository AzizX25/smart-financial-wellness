-- Preserve all accounts and ledger records. Retire only unchanged credentials
-- from the exact V2/V4 fixture identities; independently changed passwords stay intact.
-- Fingerprints identify the old BCrypt hashes without republishing credentials.
UPDATE mock_bank.bank_customer
SET status = 'INACTIVE',
    password_hash = crypt(gen_random_uuid()::text || gen_random_uuid()::text, gen_salt('bf', 12))
WHERE (id, external_customer_id) IN (
    ('11111111-1111-1111-1111-111111111111'::uuid, 'MOCK-CUST-001'),
    ('22222222-2222-2222-2222-222222222222'::uuid, 'MOCK-CUST-002')
)
AND encode(digest(password_hash, 'sha256'), 'hex') =
    'b9f803d4bae2a45d8ed9c445f77336a31fcacc5d893f7dcdbe1df0c0262e9af3';

UPDATE mock_bank.bank_administrator
SET status = 'INACTIVE',
    password_hash = crypt(gen_random_uuid()::text || gen_random_uuid()::text, gen_salt('bf', 12))
WHERE id = '30000000-0000-0000-0000-000000000001'::uuid
AND encode(digest(password_hash, 'sha256'), 'hex') =
    'f5ad9aaa4fc43ac3d888c51be4a05adb2c460a03aefe52a08f1e8bc8ad964e5b';
