-- Deletes businesses (tenants) and everything they own, straight in the database. Same algorithm as the backend's TenantPurgeService:
-- every table with a tenant_id column (or a foreign key to core.tenants), plus every table that hangs off one of those by a foreign key,
-- is cleaned children-first; then the patient / customer / staff accounts that belonged to nothing else are removed.
--
--   psql ... -v slugs=smoke-shop,tarek-test -v finish=ROLLBACK -f wipe-tenants.sql     # dry run, prints what would go
--   psql ... -v slugs=smoke-shop,tarek-test -v finish=COMMIT   -f wipe-tenants.sql     # for real
-- Stored files (uploads/*/tenants/<id>) are NOT touched here: delete them afterwards for the ids printed at the top.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('wipe.slugs', :'slugs', true);

DO $wipe$
DECLARE
  slugs text[] := string_to_array(current_setting('wipe.slugs'), ',');
  tids uuid[];
  kept uuid[];
  r record;
  n bigint;
  before_kept bigint;
  after_kept bigint;
  remaining text[];
  ready text[];
  v_tbl text;
BEGIN
  SELECT array_agg(id) INTO tids FROM core.tenants WHERE slug = ANY(slugs);
  IF coalesce(array_length(tids, 1), 0) <> array_length(slugs, 1) THEN RAISE EXCEPTION 'Some slugs do not exist: %', slugs; END IF;
  SELECT coalesce(array_agg(id), '{}') INTO kept FROM core.tenants WHERE NOT (id = ANY(tids));
  RAISE NOTICE 'Deleting % tenants: %', array_length(tids, 1), (SELECT string_agg(slug || '=' || id, ', ') FROM core.tenants WHERE id = ANY(tids));
  RAISE NOTICE 'Keeping % tenants', coalesce(array_length(kept, 1), 0);

  -- foreign keys (single column only) and the tables that belong to a tenant
  CREATE TEMP TABLE w_fk ON COMMIT DROP AS
    SELECT quote_ident(cn.nspname) || '.' || quote_ident(c.relname) AS child, quote_ident((SELECT a.attname FROM pg_attribute a WHERE a.attrelid = k.conrelid AND a.attnum = k.conkey[1])) AS ccol,
           quote_ident(pn.nspname) || '.' || quote_ident(p.relname) AS parent, quote_ident((SELECT a.attname FROM pg_attribute a WHERE a.attrelid = k.confrelid AND a.attnum = k.confkey[1])) AS pcol,
           array_length(k.conkey, 1) AS ncols
    FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
    JOIN pg_class p ON p.oid = k.confrelid JOIN pg_namespace pn ON pn.oid = p.relnamespace WHERE k.contype = 'f';
  IF EXISTS (SELECT 1 FROM w_fk WHERE ncols <> 1) THEN RAISE EXCEPTION 'Composite foreign key found: %', (SELECT string_agg(child, ',') FROM w_fk WHERE ncols <> 1); END IF;

  CREATE TEMP TABLE w_roots ON COMMIT DROP AS
    SELECT DISTINCT quote_ident(c.table_schema) || '.' || quote_ident(c.table_name) AS tbl, TRUE AS has_tid
    FROM information_schema.columns c JOIN information_schema.tables x ON x.table_schema = c.table_schema AND x.table_name = c.table_name AND x.table_type = 'BASE TABLE'
    WHERE c.column_name = 'tenant_id' AND c.table_schema NOT IN ('information_schema', 'pg_catalog')
    UNION
    SELECT child, FALSE FROM w_fk WHERE parent = 'core.tenants' AND child NOT IN (SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.columns WHERE column_name = 'tenant_id');
  DELETE FROM w_roots WHERE tbl = 'core.tenants';

  CREATE TEMP TABLE w_scoped ON COMMIT DROP AS SELECT tbl FROM w_roots;
  LOOP
    INSERT INTO w_scoped SELECT DISTINCT f.child FROM w_fk f WHERE f.parent IN (SELECT tbl FROM w_scoped) AND f.child NOT IN (SELECT tbl FROM w_scoped) AND f.child <> 'core.tenants';
    GET DIAGNOSTICS n = ROW_COUNT;
    EXIT WHEN n = 0;
  END LOOP;

  -- identities that may become orphans, collected before their links disappear
  CREATE TEMP TABLE w_pa ON COMMIT DROP AS
    SELECT user_id AS id FROM medical.patients WHERE tenant_id = ANY(tids) AND user_id IS NOT NULL
    UNION SELECT guardian_user_id FROM medical.patient_guardians WHERE tenant_id = ANY(tids);

  -- the condition that selects one tenant's rows of a table (columns unqualified)
  CREATE OR REPLACE FUNCTION pg_temp.w_pred(tbl text, ids uuid[], path text[]) RETURNS text LANGUAGE plpgsql AS $f$
  DECLARE parts text[] := '{}'; f record; rt record;
  BEGIN
    SELECT * INTO rt FROM w_roots WHERE w_roots.tbl = w_pred.tbl;
    IF FOUND THEN
      IF rt.has_tid THEN parts := parts || ('"tenant_id" = ANY(' || quote_literal(ids::text) || '::uuid[])'); END IF;
      FOR f IN SELECT * FROM w_fk WHERE child = w_pred.tbl AND parent = 'core.tenants' AND ccol <> '"tenant_id"' LOOP
        parts := parts || (f.ccol || ' = ANY(' || quote_literal(ids::text) || '::uuid[])');
      END LOOP;
    ELSE
      IF w_pred.tbl = ANY(path) THEN RAISE EXCEPTION 'Foreign key cycle at %', w_pred.tbl; END IF;
      FOR f IN SELECT * FROM w_fk WHERE child = w_pred.tbl AND parent IN (SELECT s.tbl FROM w_scoped s) AND parent <> w_pred.tbl LOOP
        parts := parts || (f.ccol || ' IN (SELECT ' || f.pcol || ' FROM ' || f.parent || ' WHERE ' || pg_temp.w_pred(f.parent, ids, path || w_pred.tbl) || ')');
      END LOOP;
    END IF;
    IF array_length(parts, 1) IS NULL THEN RAISE EXCEPTION 'Cannot scope table %', w_pred.tbl; END IF;
    RETURN '(' || array_to_string(parts, ' OR ') || ')';
  END $f$;

  -- rows of the tenants that stay: must be identical afterwards
  before_kept := 0;
  FOR v_tbl IN SELECT s.tbl FROM w_scoped s LOOP
    EXECUTE 'SELECT count(*) FROM ' || v_tbl || ' WHERE ' || pg_temp.w_pred(v_tbl, kept, '{}') INTO n;
    before_kept := before_kept + n;
  END LOOP;

  -- delete children first: a table goes when no remaining scoped table still points at it
  SELECT array_agg(s.tbl) INTO remaining FROM w_scoped s;
  WHILE coalesce(array_length(remaining, 1), 0) > 0 LOOP
    SELECT array_agg(t) INTO ready FROM unnest(remaining) t
      WHERE NOT EXISTS (SELECT 1 FROM w_fk f WHERE f.parent = t AND f.child <> t AND f.child = ANY(remaining));
    IF ready IS NULL THEN RAISE EXCEPTION 'Cannot order tables: %', remaining; END IF;
    FOREACH v_tbl IN ARRAY ready LOOP
      EXECUTE 'DELETE FROM ' || v_tbl || ' WHERE ' || pg_temp.w_pred(v_tbl, tids, '{}');
      GET DIAGNOSTICS n = ROW_COUNT;
      IF n > 0 THEN RAISE NOTICE '  % : % rows', v_tbl, n; END IF;
    END LOOP;
    remaining := ARRAY(SELECT t FROM unnest(remaining) t WHERE NOT (t = ANY(ready)));
  END LOOP;

  after_kept := 0;
  FOR v_tbl IN SELECT s.tbl FROM w_scoped s LOOP
    EXECUTE 'SELECT count(*) FROM ' || v_tbl || ' WHERE ' || pg_temp.w_pred(v_tbl, kept, '{}') INTO n;
    after_kept := after_kept + n;
  END LOOP;
  IF after_kept <> before_kept THEN RAISE EXCEPTION 'Kept tenants lost rows: % -> %', before_kept, after_kept; END IF;
  RAISE NOTICE 'Rows of kept tenants unchanged (%)', after_kept;

  DELETE FROM core.tenants WHERE id = ANY(tids);

  -- people who belonged to nothing any more (also leftovers of earlier deletions)
  CREATE TEMP TABLE w_gone ON COMMIT DROP AS
    SELECT a.id FROM medical.patient_accounts a
      WHERE NOT EXISTS (SELECT 1 FROM medical.patients p WHERE p.user_id = a.id) AND NOT EXISTS (SELECT 1 FROM medical.patient_guardians g WHERE g.guardian_user_id = a.id)
    UNION
    SELECT a.id FROM commerce.client_accounts a
      WHERE NOT EXISTS (SELECT 1 FROM commerce.customers c WHERE c.user_id = a.id) AND NOT EXISTS (SELECT 1 FROM commerce.orders o WHERE o.user_id = a.id)
        AND NOT EXISTS (SELECT 1 FROM commerce.wishlist_items w WHERE w.user_id = a.id) AND NOT EXISTS (SELECT 1 FROM commerce.coupon_redemptions c WHERE c.user_id = a.id)
    UNION
    SELECT u.id FROM core.users u
      WHERE NOT EXISTS (SELECT 1 FROM core.user_tenant_memberships m WHERE m.user_id = u.id) AND NOT EXISTS (SELECT 1 FROM core.user_platform_roles pr WHERE pr.user_id = u.id)
        AND NOT EXISTS (SELECT 1 FROM medical.doctors d WHERE d.user_id = u.id);
  DELETE FROM core.user_sessions WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM core.sso_tickets WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM notifications.device_tokens WHERE principal_id IN (SELECT id FROM w_gone);
  DELETE FROM notifications.notifications WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM notifications.outbox WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM core.activation_pins WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM commerce.addresses WHERE user_id IN (SELECT id FROM w_gone);
  DELETE FROM medical.patient_accounts WHERE id IN (SELECT id FROM w_gone); GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'patient accounts removed: %', n;
  DELETE FROM commerce.client_accounts WHERE id IN (SELECT id FROM w_gone); GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'client accounts removed: %', n;
  DELETE FROM core.users WHERE id IN (SELECT id FROM w_gone); GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'staff users removed: %', n;
END
$wipe$;

SELECT slug, tenant_type, status FROM core.tenants ORDER BY created_at;
SELECT (SELECT count(*) FROM core.users) AS users, (SELECT count(*) FROM medical.patient_accounts) AS patient_accounts, (SELECT count(*) FROM commerce.client_accounts) AS client_accounts, (SELECT count(*) FROM core.files) AS files;
:finish;
