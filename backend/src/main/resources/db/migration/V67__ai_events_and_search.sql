-- What happened in each AI reply (time, the checks that corrected it, a switch to the backup model, an error): the AI health page and the alerts read it.
CREATE TABLE chat.ai_events (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    conversation_id UUID,
    channel         VARCHAR(12),
    model           VARCHAR(12),
    kind            VARCHAR(40) NOT NULL,   -- REPLY, CHECK:<name>, FALLBACK, ERROR, NEEDS_HUMAN
    detail          TEXT,
    ms              BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_events_time ON chat.ai_events (created_at);
CREATE INDEX idx_ai_events_tenant ON chat.ai_events (tenant_id, created_at);

-- Search finds a product by its category (and the parent category), in Arabic and English, by who it is for, and by its English name:
-- «أحذية رجالي» finds «بوت شتوي رجالي», "headphones" finds «سماعة لاسلكية», without a hand-written list of words.
CREATE OR REPLACE FUNCTION commerce.products_search_tg() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    cat TEXT := '';
    en  TEXT := '';
BEGIN
    IF NEW.taxonomy_id IS NOT NULL THEN
        SELECT t.name_ar || ' ' || t.name_en || ' ' || coalesce(p.name_ar || ' ' || p.name_en, '') INTO cat
        FROM commerce.taxonomy t LEFT JOIN commerce.taxonomy p ON p.id = t.parent_id WHERE t.id = NEW.taxonomy_id;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        SELECT coalesce(string_agg(name, ' '), '') INTO en FROM commerce.product_translations WHERE product_id = NEW.id;
    END IF;
    NEW.search_text := commerce.norm_ar(NEW.name || ' ' || coalesce(NEW.brand, '') || ' ' || coalesce(NEW.short_description, '') || ' ' || array_to_string(NEW.tags, ' ')
        || ' ' || coalesce(cat, '') || ' ' || coalesce(en, '') || ' ' ||
        CASE NEW.audience WHEN 'MEN' THEN 'رجالي رجال men' WHEN 'WOMEN' THEN 'حريمي نسائي women ladies' WHEN 'BOYS' THEN 'ولادي اولاد اطفال boys kids'
                          WHEN 'GIRLS' THEN 'بناتي بنات اطفال girls kids' WHEN 'BABY' THEN 'بيبي اطفال baby' ELSE '' END);
    RETURN NEW;
END $$;

DROP TRIGGER trg_products_search ON commerce.products;
CREATE TRIGGER trg_products_search BEFORE INSERT OR UPDATE OF name, brand, short_description, tags, taxonomy_id, audience ON commerce.products
    FOR EACH ROW EXECUTE FUNCTION commerce.products_search_tg();

-- a new or changed English name refreshes the product's search text
CREATE OR REPLACE FUNCTION commerce.product_translations_search_tg() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE commerce.products SET name = name WHERE id = coalesce(NEW.product_id, OLD.product_id);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_product_translations_search AFTER INSERT OR UPDATE OR DELETE ON commerce.product_translations
    FOR EACH ROW EXECUTE FUNCTION commerce.product_translations_search_tg();

UPDATE commerce.products SET name = name;
