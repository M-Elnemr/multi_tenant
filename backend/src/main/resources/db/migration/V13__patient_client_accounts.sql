-- Patients and shop clients are separate kinds of account, kept apart from staff (core.users).
--   * patient account: phone + password, created ONLY by a clinic (temp password); one account can belong to several clinics.
--   * client account: signs in with Google; clients can also order as guests (no account).
-- Ids are random UUIDs, so one id never means two different people; the tables that point at "a person" no longer force that
-- person to be a staff user.

CREATE TABLE medical.patient_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone VARCHAR(20) NOT NULL UNIQUE,
    display_name VARCHAR(150) NOT NULL DEFAULT '',
    password_hash VARCHAR(255) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commerce.client_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(254),
    name VARCHAR(150) NOT NULL DEFAULT '',
    phone VARCHAR(20),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sessions now belong to any kind of principal.
ALTER TABLE core.user_sessions ADD COLUMN principal_type VARCHAR(10) NOT NULL DEFAULT 'STAFF' CHECK (principal_type IN ('STAFF', 'PATIENT', 'CLIENT'));

-- Existing customer / patient logins start clean: the clinical and order records stay, only the account links go.
-- 1. keep who the customers were (name + phone) on the customer row before the login disappears
ALTER TABLE commerce.customers ADD COLUMN name VARCHAR(150);
ALTER TABLE commerce.customers ADD COLUMN phone VARCHAR(30);
ALTER TABLE commerce.customers ADD COLUMN email VARCHAR(254);
ALTER TABLE commerce.customers ADD COLUMN address_json JSONB;
UPDATE commerce.customers c SET name = btrim(u.first_name || ' ' || u.last_name), phone = u.phone, email = u.email
FROM core.users u WHERE u.id = c.user_id;
UPDATE commerce.customers c SET name = coalesce((SELECT o.customer_name_snapshot FROM commerce.orders o WHERE o.customer_id = c.id ORDER BY o.created_at DESC LIMIT 1), 'Customer')
WHERE c.name IS NULL;
-- two old customers of one store could share no phone; make phones unique per store before adding the index
UPDATE commerce.customers c SET phone = NULL
WHERE phone IS NOT NULL AND EXISTS (SELECT 1 FROM commerce.customers d WHERE d.tenant_id = c.tenant_id AND d.phone = c.phone AND d.id < c.id);
UPDATE commerce.customers SET user_id = NULL;
CREATE UNIQUE INDEX uq_customers_tenant_phone ON commerce.customers (tenant_id, phone) WHERE phone IS NOT NULL;

UPDATE commerce.orders SET user_id = NULL;
UPDATE medical.patients SET user_id = NULL;
DELETE FROM medical.patient_guardians;
DELETE FROM commerce.addresses;
DELETE FROM commerce.wishlist_items;
UPDATE commerce.coupon_redemptions SET user_id = '00000000-0000-0000-0000-000000000000';
DELETE FROM notifications.notifications n USING core.users u WHERE n.user_id = u.id AND NOT EXISTS (
    SELECT 1 FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id
    WHERE m.user_id = u.id AND r.code NOT IN ('CUSTOMER', 'PATIENT', 'GUARDIAN'));

-- (drop the old links to core.users first, so removing those people cannot be blocked)
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
        WHERE contype = 'f' AND confrelid = 'core.users'::regclass
          AND conrelid::regclass::text IN ('core.user_sessions', 'commerce.customers', 'commerce.addresses', 'commerce.orders',
                                           'notifications.notifications', 'core.files', 'medical.patients', 'medical.patient_guardians')
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;
END $$;

-- 2. remove patient/customer-only people from the staff table
CREATE TEMP TABLE _gone AS
SELECT u.id FROM core.users u
WHERE NOT EXISTS (SELECT 1 FROM core.user_platform_roles pr WHERE pr.user_id = u.id)
  AND NOT EXISTS (
    SELECT 1 FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id
    WHERE m.user_id = u.id AND r.code NOT IN ('CUSTOMER', 'PATIENT', 'GUARDIAN'))
  AND NOT EXISTS (SELECT 1 FROM medical.doctors d WHERE d.user_id = u.id);
DELETE FROM core.membership_roles WHERE membership_id IN (SELECT id FROM core.user_tenant_memberships WHERE user_id IN (SELECT id FROM _gone));
DELETE FROM core.user_tenant_memberships WHERE user_id IN (SELECT id FROM _gone);
DELETE FROM core.user_sessions WHERE user_id IN (SELECT id FROM _gone);
DELETE FROM core.activation_pins WHERE user_id IN (SELECT id FROM _gone);
DELETE FROM core.sso_tickets WHERE user_id IN (SELECT id FROM _gone);
DELETE FROM core.users WHERE id IN (SELECT id FROM _gone);
DROP TABLE _gone;

-- 3. re-point / relax the foreign keys
ALTER TABLE medical.patients ADD CONSTRAINT patients_account_fk FOREIGN KEY (user_id) REFERENCES medical.patient_accounts(id);
ALTER TABLE medical.patient_guardians ADD CONSTRAINT guardians_account_fk FOREIGN KEY (guardian_user_id) REFERENCES medical.patient_accounts(id);
ALTER TABLE commerce.customers ADD CONSTRAINT customers_account_fk FOREIGN KEY (user_id) REFERENCES commerce.client_accounts(id);
ALTER TABLE commerce.orders ADD CONSTRAINT orders_account_fk FOREIGN KEY (user_id) REFERENCES commerce.client_accounts(id);
ALTER TABLE commerce.addresses ADD CONSTRAINT addresses_account_fk FOREIGN KEY (user_id) REFERENCES commerce.client_accounts(id);
-- (user_sessions, notifications and files keep the principal id without a foreign key: it may be staff, patient or client)

-- 4. a store customer can be a guest (no account): keyed by phone
ALTER TABLE commerce.customers ALTER COLUMN name SET NOT NULL;
UPDATE commerce.coupon_redemptions SET user_id = coalesce((SELECT o.customer_id FROM commerce.orders o WHERE o.id = order_id), user_id);

-- 5. order statuses: REQUESTED -> PREPARING -> SHIPPED -> ARRIVED, plus CANCELLED and RETURNED
UPDATE commerce.orders SET status = CASE status
    WHEN 'CONFIRMED' THEN 'REQUESTED' WHEN 'PROCESSING' THEN 'PREPARING' WHEN 'PACKED' THEN 'PREPARING'
    WHEN 'OUT_FOR_DELIVERY' THEN 'SHIPPED' WHEN 'DELIVERED' THEN 'ARRIVED' WHEN 'RETURN_REQUESTED' THEN 'RETURNED' ELSE status END;
UPDATE commerce.order_status_history SET new_status = CASE new_status
    WHEN 'CONFIRMED' THEN 'REQUESTED' WHEN 'PROCESSING' THEN 'PREPARING' WHEN 'PACKED' THEN 'PREPARING'
    WHEN 'OUT_FOR_DELIVERY' THEN 'SHIPPED' WHEN 'DELIVERED' THEN 'ARRIVED' WHEN 'RETURN_REQUESTED' THEN 'RETURNED' ELSE new_status END;
UPDATE commerce.order_status_history SET from_status = CASE from_status
    WHEN 'CONFIRMED' THEN 'REQUESTED' WHEN 'PROCESSING' THEN 'PREPARING' WHEN 'PACKED' THEN 'PREPARING'
    WHEN 'OUT_FOR_DELIVERY' THEN 'SHIPPED' WHEN 'DELIVERED' THEN 'ARRIVED' WHEN 'RETURN_REQUESTED' THEN 'RETURNED' ELSE from_status END;

-- 6. tests: laboratory or radiology, and a simple Done state
ALTER TABLE medical.lab_orders ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'LAB' CHECK (kind IN ('LAB', 'RADIOLOGY'));
ALTER TABLE medical.lab_orders DROP CONSTRAINT IF EXISTS lab_orders_status_check;
ALTER TABLE medical.lab_orders ADD CONSTRAINT lab_orders_status_check CHECK (status IN ('ORDERED', 'PATIENT_UPLOADED', 'UNDER_REVIEW', 'REVIEWED', 'CANCELLED', 'DONE'));
ALTER TABLE medical.lab_orders ADD COLUMN done_at TIMESTAMPTZ;

-- 7. what a patient can see about the doctor
ALTER TABLE medical.doctors ADD COLUMN public_phone VARCHAR(30);

-- 8. push notifications (Firebase Cloud Messaging) for patient phones
CREATE TABLE notifications.device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id UUID NOT NULL,
    token VARCHAR(512) NOT NULL UNIQUE,
    platform VARCHAR(10) NOT NULL DEFAULT 'ANDROID',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_tokens_principal ON notifications.device_tokens (principal_id);
