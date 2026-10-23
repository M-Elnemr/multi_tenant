-- A product picture can belong to one option value (usually a colour): choosing that colour on the product page shows its pictures.
-- NULL = a general picture that belongs to every colour.
ALTER TABLE commerce.product_media ADD COLUMN option_value_id UUID REFERENCES commerce.product_option_values(id) ON DELETE SET NULL;
CREATE INDEX idx_product_media_value ON commerce.product_media (option_value_id) WHERE option_value_id IS NOT NULL;
