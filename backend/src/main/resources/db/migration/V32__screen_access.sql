-- Per-screen employee access: hidden / read only / read & write -------------------------------------------------
-- New read-only permissions for screens whose only permission used to cover both reading and writing.
INSERT INTO core.permissions (code, name) VALUES
 ('product.read','View products'),('inventory.read','View inventory'),('coupon.read','View coupons'),('review.read','View reviews'),
 ('settings.read','View settings'),('shipping.read','View shipping & payment methods'),('domain.read','View domains'),
 ('schedule.read','View schedules & services'),('appointment.read','View appointments'),
 ('medical_note.read','View visits'),('prescription.read','View prescriptions'),('lab_order.read','View lab orders');

-- Everyone who could already write a screen keeps full access: grant the matching read permission.
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT DISTINCT rp.role_id, rd.id
FROM core.role_permissions rp
JOIN core.permissions wr ON wr.id = rp.permission_id
JOIN (VALUES
    ('product.create','product.read'),('product.update','product.read'),
    ('inventory.adjust','inventory.read'),('coupon.manage','coupon.read'),('review.moderate','review.read'),
    ('settings.manage','settings.read'),('shipping.manage','shipping.read'),('domain.manage','domain.read'),
    ('schedule.manage','schedule.read'),('appointment.manage','appointment.read'),
    ('medical_note.create','medical_note.read'),('prescription.create','prescription.read'),('lab_order.create','lab_order.read')
) AS m(write_code, read_code) ON m.write_code = wr.code
JOIN core.permissions rd ON rd.code = m.read_code
ON CONFLICT DO NOTHING;

-- Anchor roles for employees whose access is chosen screen by screen (they carry no permissions of their own).
INSERT INTO core.roles (code, name, scope) VALUES
 ('STORE_STAFF','Store employee (custom access)','TENANT'),('CLINIC_STAFF','Clinic employee (custom access)','TENANT');

CREATE TABLE core.membership_screen_access (
    membership_id UUID NOT NULL REFERENCES core.user_tenant_memberships(id) ON DELETE CASCADE,
    screen        VARCHAR(40) NOT NULL,
    level         VARCHAR(10) NOT NULL CHECK (level IN ('READ','WRITE')),
    PRIMARY KEY (membership_id, screen)
);
