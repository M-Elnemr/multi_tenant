-- Design revolution: per-site colour customization. Nullable JSONB, so every existing site renders exactly as before until its owner changes it.
-- Shape: {"tokens": {"primary":"#rrggbb", ...}} — validated in the backend (hex only, token whitelist, contrast floor).
ALTER TABLE commerce.store_profiles ADD COLUMN IF NOT EXISTS site_theme JSONB;
ALTER TABLE medical.clinic_profiles ADD COLUMN IF NOT EXISTS site_theme JSONB;
ALTER TABLE legal.profiles          ADD COLUMN IF NOT EXISTS site_theme JSONB;
