-- Catalog: Arabic-aware search, richer products, homepage banners.
CREATE OR REPLACE FUNCTION commerce.norm_ar(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT lower(translate(regexp_replace(coalesce(t, ''), '[ً-ٰٟـ]', '', 'g'), 'أإآٱىة', 'اااايه'))
$$;

ALTER TABLE commerce.products
    ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN badge VARCHAR(20) NOT NULL DEFAULT '',
    ADD COLUMN specs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN size_guide TEXT NOT NULL DEFAULT '',
    ADD COLUMN is_featured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0,
    ADD COLUMN sold_count INT NOT NULL DEFAULT 0,
    ADD COLUMN search_text TEXT NOT NULL DEFAULT '';

CREATE OR REPLACE FUNCTION commerce.products_search_tg() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_text := commerce.norm_ar(NEW.name || ' ' || coalesce(NEW.brand, '') || ' ' || coalesce(NEW.short_description, '') || ' ' || array_to_string(NEW.tags, ' '));
    RETURN NEW;
END $$;

CREATE TRIGGER trg_products_search BEFORE INSERT OR UPDATE OF name, brand, short_description, tags ON commerce.products
    FOR EACH ROW EXECUTE FUNCTION commerce.products_search_tg();
UPDATE commerce.products SET name = name;
CREATE INDEX idx_products_search_trgm ON commerce.products USING gin (search_text gin_trgm_ops);
CREATE INDEX idx_products_featured ON commerce.products (tenant_id) WHERE is_featured;
CREATE INDEX idx_categories_parent ON commerce.categories (tenant_id, parent_id);

CREATE TABLE commerce.banners (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    image_file_id UUID NOT NULL,
    title VARCHAR(120) NOT NULL DEFAULT '',
    subtitle VARCHAR(200) NOT NULL DEFAULT '',
    link_url VARCHAR(300) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_banners_tenant ON commerce.banners (tenant_id, sort_order);

CREATE TABLE commerce.stock_notify_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id),
    product_id UUID NOT NULL REFERENCES commerce.products(id),
    phone VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, phone)
);
