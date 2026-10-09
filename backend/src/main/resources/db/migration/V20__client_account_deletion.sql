-- Shop clients can delete their own account (Google Play policy): the row stays only as an anonymous stub so old orders keep their foreign key.
ALTER TABLE commerce.client_accounts DROP CONSTRAINT IF EXISTS client_accounts_status_check;
ALTER TABLE commerce.client_accounts ADD CONSTRAINT client_accounts_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'));
ALTER TABLE commerce.client_accounts ADD COLUMN deleted_at TIMESTAMPTZ;
-- (the stub keeps the same id, so orders and reviews that point at it stay valid)


