-- Round 5: where an order came from, barcode uniqueness for the till, and returns by item.

-- 1. The sales channel of every order. "source" stays as it is (reports and Copilot read it); "channel" is the owner-facing split.
ALTER TABLE commerce.orders ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'WEBSITE';
UPDATE commerce.orders SET channel = CASE source WHEN 'POS' THEN 'POS' WHEN 'OPEN' THEN 'OTHER' ELSE 'WEBSITE' END;
UPDATE commerce.orders o SET channel = c.channel
FROM chat.conversations c
WHERE o.source = 'AI_CHAT' AND o.idempotency_key LIKE 'chat-' || c.id::text || '-%' AND c.channel IN ('FACEBOOK', 'INSTAGRAM', 'WHATSAPP');
CREATE INDEX idx_orders_channel ON commerce.orders (tenant_id, channel, created_at DESC);

-- 2. A barcode identifies one variant of a shop. Older duplicates lose their barcode (the oldest keeps it) so the index can be created.
UPDATE commerce.product_variants v SET barcode = NULL
WHERE barcode IS NOT NULL AND (barcode = '' OR EXISTS (
    SELECT 1 FROM commerce.product_variants w WHERE w.tenant_id = v.tenant_id AND w.barcode = v.barcode AND (w.created_at, w.id) < (v.created_at, v.id)));
CREATE UNIQUE INDEX uq_variant_barcode ON commerce.product_variants (tenant_id, barcode) WHERE barcode IS NOT NULL;

-- 3. Returns by item: what came back, what was refunded, and the credit note that goes with it.
CREATE TABLE commerce.order_returns (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    order_id     UUID NOT NULL REFERENCES commerce.orders(id),
    refund_minor BIGINT NOT NULL DEFAULT 0,
    reason       VARCHAR(300),
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_returns_order ON commerce.order_returns (order_id);
CREATE TABLE commerce.order_return_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_id     UUID NOT NULL REFERENCES commerce.order_returns(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES commerce.order_items(id),
    quantity      INT NOT NULL CHECK (quantity > 0),
    amount_minor  BIGINT NOT NULL
);
CREATE INDEX idx_order_return_items_item ON commerce.order_return_items (order_item_id);

-- A credit note can now belong to one return (several per order); the old whole-order credit note keeps return_id empty.
ALTER TABLE commerce.invoices ADD COLUMN return_id UUID REFERENCES commerce.order_returns(id);
DROP INDEX commerce.uq_credit_note_per_order;
CREATE UNIQUE INDEX uq_credit_note_per_order ON commerce.invoices (order_id) WHERE kind = 'CREDIT_NOTE' AND return_id IS NULL;
CREATE UNIQUE INDEX uq_credit_note_per_return ON commerce.invoices (return_id) WHERE return_id IS NOT NULL;
