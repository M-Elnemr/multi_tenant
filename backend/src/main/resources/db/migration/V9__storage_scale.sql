-- Files: which bucket holds the objects, every generated image variant, and the public path of immutable variants.
ALTER TABLE core.files
    ADD COLUMN bucket VARCHAR(8) NOT NULL DEFAULT 'PRIVATE' CHECK (bucket IN ('PUBLIC','PRIVATE')),
    ADD COLUMN variants JSONB,
    ADD COLUMN stored_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN public_base VARCHAR(300),
    ADD COLUMN public_ext VARCHAR(8);
ALTER TABLE commerce.product_media ADD COLUMN media_base VARCHAR(300), ADD COLUMN media_ext VARCHAR(8);

-- Storage allowance per plan (MB). NULL limit = unlimited.
INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value)
SELECT id, 'max_storage_mb', TRUE, CASE code WHEN 'STORE_STARTER' THEN 1024 WHEN 'STORE_PRO' THEN 10240 WHEN 'CLINIC_STARTER' THEN 2048 ELSE 20480 END
FROM billing.subscription_plans ON CONFLICT DO NOTHING;

-- Indexes for the hot paths at 1000+ tenants (all lead with tenant_id).
CREATE INDEX IF NOT EXISTS idx_orders_tenant_payment ON commerce.orders (tenant_id, payment_status, status);
CREATE INDEX IF NOT EXISTS idx_appt_tenant_status_day ON medical.appointments (tenant_id, status, start_at);
CREATE INDEX IF NOT EXISTS idx_products_tenant_created ON commerce.products (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_outbox_sent ON notifications.outbox (created_at) WHERE status <> 'PENDING';
CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications.notifications (created_at) WHERE read_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_idempotency_created ON core.idempotency_keys (created_at);
CREATE INDEX IF NOT EXISTS idx_webhook_created ON billing.webhook_events (created_at);
