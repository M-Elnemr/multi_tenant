-- Permission catalogue (spec section 6 / 58) ---------------------------------
INSERT INTO core.permissions (code, name) VALUES
 ('settings.manage','Manage settings'),('staff.manage','Manage staff'),('domain.manage','Manage domains'),
 ('billing.read','View billing'),('billing.manage','Manage billing'),('report.read','View reports'),
 ('product.create','Create products'),('product.update','Update products'),('product.delete','Delete products'),
 ('inventory.adjust','Adjust inventory'),('order.read','View orders'),('order.update_status','Update order status'),
 ('refund.create','Create refunds'),('customer.read','View customers'),
 ('patient.read','View patients'),('patient.update','Update patients'),('appointment.manage','Manage appointments'),
 ('medical_note.create','Create medical notes'),('prescription.create','Create prescriptions'),
 ('prescription.delete','Cancel prescriptions'),('lab_order.create','Create lab orders'),
 ('platform.tenant.manage','Manage tenants (platform)'),('platform.audit.read','Read audit (platform)');

-- Predefined roles --------------------------------------------------------------
INSERT INTO core.roles (code, name, scope) VALUES
 ('PLATFORM_OWNER','Platform owner','PLATFORM'),('PLATFORM_ADMIN','Platform admin','PLATFORM'),
 ('PLATFORM_SUPPORT','Platform support','PLATFORM'),('PLATFORM_FINANCE','Platform finance','PLATFORM'),
 ('PLATFORM_AUDITOR','Platform auditor','PLATFORM'),
 ('STORE_OWNER','Store owner','TENANT'),('STORE_ADMIN','Store admin','TENANT'),('STORE_MANAGER','Store manager','TENANT'),
 ('INVENTORY_MANAGER','Inventory manager','TENANT'),('ORDER_MANAGER','Order manager','TENANT'),
 ('CUSTOMER_SUPPORT','Customer support','TENANT'),('REPORT_VIEWER','Report viewer','TENANT'),
 ('CLINIC_OWNER','Clinic owner','TENANT'),('DOCTOR','Doctor','TENANT'),('CLINIC_ADMIN','Clinic admin','TENANT'),
 ('RECEPTIONIST','Receptionist','TENANT'),('NURSE','Nurse','TENANT'),('LAB_COORDINATOR','Lab coordinator','TENANT'),
 ('ACCOUNTANT','Accountant','TENANT'),
 ('CUSTOMER','Customer','TENANT'),('PATIENT','Patient','TENANT'),('GUARDIAN','Guardian','TENANT');

CREATE OR REPLACE FUNCTION pg_temp.grant_perms(role_code TEXT, perm_codes TEXT[]) RETURNS VOID AS $$
    INSERT INTO core.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM core.roles r, core.permissions p
    WHERE r.code = role_code AND r.tenant_id IS NULL AND p.code = ANY(perm_codes);
$$ LANGUAGE sql;

SELECT pg_temp.grant_perms('STORE_OWNER', ARRAY['settings.manage','staff.manage','domain.manage','billing.read','billing.manage','report.read','product.create','product.update','product.delete','inventory.adjust','order.read','order.update_status','refund.create','customer.read']);
SELECT pg_temp.grant_perms('STORE_ADMIN', ARRAY['settings.manage','staff.manage','report.read','product.create','product.update','product.delete','inventory.adjust','order.read','order.update_status','refund.create','customer.read']);
SELECT pg_temp.grant_perms('STORE_MANAGER', ARRAY['report.read','product.create','product.update','inventory.adjust','order.read','order.update_status','customer.read']);
SELECT pg_temp.grant_perms('INVENTORY_MANAGER', ARRAY['product.update','inventory.adjust']);
SELECT pg_temp.grant_perms('ORDER_MANAGER', ARRAY['order.read','order.update_status']);
SELECT pg_temp.grant_perms('CUSTOMER_SUPPORT', ARRAY['order.read','customer.read']);
SELECT pg_temp.grant_perms('REPORT_VIEWER', ARRAY['report.read']);
SELECT pg_temp.grant_perms('CLINIC_OWNER', ARRAY['settings.manage','staff.manage','domain.manage','billing.read','billing.manage','report.read','patient.read','patient.update','appointment.manage','medical_note.create','prescription.create','prescription.delete','lab_order.create']);
SELECT pg_temp.grant_perms('DOCTOR', ARRAY['patient.read','patient.update','appointment.manage','medical_note.create','prescription.create','prescription.delete','lab_order.create','report.read']);
SELECT pg_temp.grant_perms('CLINIC_ADMIN', ARRAY['settings.manage','staff.manage','report.read','patient.read','patient.update','appointment.manage']);
SELECT pg_temp.grant_perms('RECEPTIONIST', ARRAY['patient.read','patient.update','appointment.manage']);
SELECT pg_temp.grant_perms('NURSE', ARRAY['patient.read']);
SELECT pg_temp.grant_perms('LAB_COORDINATOR', ARRAY['patient.read','lab_order.create']);
SELECT pg_temp.grant_perms('ACCOUNTANT', ARRAY['billing.read','report.read']);
SELECT pg_temp.grant_perms('PLATFORM_ADMIN', ARRAY['platform.tenant.manage']);
SELECT pg_temp.grant_perms('PLATFORM_OWNER', ARRAY['platform.tenant.manage','platform.audit.read']);
SELECT pg_temp.grant_perms('PLATFORM_AUDITOR', ARRAY['platform.audit.read']);
