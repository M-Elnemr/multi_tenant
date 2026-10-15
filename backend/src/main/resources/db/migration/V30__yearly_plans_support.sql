-- Annual billing: two months free (12 months for the price of 10). Same features as the monthly plan of the same level.
INSERT INTO billing.subscription_plans (code, tenant_type, name, price_minor, interval, is_trial_plan, is_active, sort_order) VALUES
 ('STORE_STANDARD_YEARLY','STORE','Standard (yearly)', 500000, 'YEAR', FALSE, TRUE, 25),
 ('STORE_PRO_YEARLY','STORE','Professional (yearly)', 750000, 'YEAR', FALSE, TRUE, 35),
 ('CLINIC_PRO_YEARLY','CLINIC','Professional (yearly)', 1000000, 'YEAR', FALSE, TRUE, 35);

INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT n.id, f.feature_key, f.enabled, f.limit_value
FROM billing.subscription_plans n
JOIN billing.subscription_plans src ON src.code = replace(n.code, '_YEARLY', '')
JOIN billing.subscription_plan_features f ON f.plan_id = src.id
WHERE n.code IN ('STORE_STANDARD_YEARLY','STORE_PRO_YEARLY','CLINIC_PRO_YEARLY');

-- Support access: the platform owner can open any business as if they were its owner (every use is audited).
INSERT INTO core.permissions (code, name) VALUES ('platform.support.access','Open any business as its owner (platform support)');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code = 'PLATFORM_OWNER' AND r.tenant_id IS NULL AND p.code = 'platform.support.access';
