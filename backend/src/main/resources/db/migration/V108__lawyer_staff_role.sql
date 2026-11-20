-- A lawyer office's employee with chosen screens (like STORE_STAFF and CLINIC_STAFF): a role of its own, so a lawyer is never described as a clinic employee. Permissions come from the chosen screens.
INSERT INTO core.roles (code, name, scope) VALUES ('LAWYER_STAFF', 'Law office employee (custom access)', 'TENANT') ON CONFLICT DO NOTHING;
