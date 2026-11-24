-- The stock of a variant is read by variant id alone (the catalog for the AI, product search, product details), but inventory_items had indexes only on (branch_id, variant_id)
-- and (tenant_id, variant_id), so each read scanned the whole table: 2.5 s to build one 60-product catalog on a 168k-row table. This index makes it a lookup.
CREATE INDEX IF NOT EXISTS idx_inventory_variant ON commerce.inventory_items (variant_id);
