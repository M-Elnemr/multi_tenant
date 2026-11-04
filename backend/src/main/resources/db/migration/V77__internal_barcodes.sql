-- Every sellable product option gets a barcode, so it sells by scanning at the till and prints on a label. Options without a manufacturer barcode get the shop's own
-- internal EAN-13 in the 20… range (GS1 keeps it for in-store use): "20", ten digits counting up per shop, and the check digit. New ones are given by the app on save.
CREATE OR REPLACE FUNCTION pg_temp.ean13(twelve TEXT) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE s INT := 0; i INT;
BEGIN
    FOR i IN 1..12 LOOP s := s + substr(twelve, i, 1)::INT * CASE WHEN i % 2 = 1 THEN 1 ELSE 3 END; END LOOP;
    RETURN twelve || ((10 - s % 10) % 10)::TEXT;
END $$;

DO $$
DECLARE t RECORD; v RECORD; n BIGINT; code TEXT;
BEGIN
    FOR t IN SELECT DISTINCT tenant_id FROM commerce.product_variants WHERE barcode IS NULL AND status = 'ACTIVE' LOOP
        SELECT coalesce(max(substring(barcode, 3, 10)::BIGINT), 0) INTO n FROM commerce.product_variants WHERE tenant_id = t.tenant_id AND barcode ~ '^20[0-9]{11}$';
        FOR v IN SELECT id FROM commerce.product_variants WHERE tenant_id = t.tenant_id AND barcode IS NULL AND status = 'ACTIVE' ORDER BY created_at, id LOOP
            LOOP
                n := n + 1;
                code := pg_temp.ean13('20' || lpad(n::TEXT, 10, '0'));
                EXIT WHEN NOT EXISTS (SELECT 1 FROM commerce.product_variants WHERE tenant_id = t.tenant_id AND barcode = code);
            END LOOP;
            UPDATE commerce.product_variants SET barcode = code, updated_at = now() WHERE id = v.id;   -- updated_at: open tills pick the new code up with their next refresh
        END LOOP;
    END LOOP;
END $$;
