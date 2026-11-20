-- SERVICE: a fourth business type (salons, barbers, plumbers, repair, cleaning...). It runs on the shop's building blocks and has its own plan, which copies the shop's: free forever, paid with points.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
             WHERE contype = 'c' AND pg_get_constraintdef(oid) LIKE '%tenant_type%' AND conrelid IN ('core.tenants'::regclass, 'billing.subscription_plans'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
    END LOOP;
END $$;
ALTER TABLE core.tenants ADD CONSTRAINT tenants_tenant_type_check CHECK (tenant_type IN ('STORE','CLINIC','LAWYER','SERVICE'));
ALTER TABLE billing.subscription_plans ADD CONSTRAINT subscription_plans_tenant_type_check CHECK (tenant_type IN ('STORE','CLINIC','LAWYER','SERVICE'));

INSERT INTO billing.subscription_plans (code, tenant_type, name, price_minor, interval, is_trial_plan, is_active, sort_order)
SELECT 'SERVICE_FREE', 'SERVICE', 'Free', 0, 'MONTH', FALSE, TRUE, 10
WHERE NOT EXISTS (SELECT 1 FROM billing.subscription_plans WHERE code = 'SERVICE_FREE');

INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT (SELECT id FROM billing.subscription_plans WHERE code = 'SERVICE_FREE'), f.feature_key, f.enabled, f.limit_value
FROM billing.subscription_plan_features f
WHERE f.plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_FREE')
  AND NOT EXISTS (SELECT 1 FROM billing.subscription_plan_features x WHERE x.plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'SERVICE_FREE') AND x.feature_key = f.feature_key);
