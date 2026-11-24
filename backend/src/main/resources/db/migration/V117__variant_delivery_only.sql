-- A variant (one size of a product) that the shop delivers only to some governorates, e.g. TVs of 50 inches and up by the shop's own truck to Cairo and Giza, as governorate codes.
-- NULL means the shop's normal delivery areas. Backward compatible: one nullable column.
ALTER TABLE commerce.product_variants ADD COLUMN IF NOT EXISTS delivery_only VARCHAR(3)[];
