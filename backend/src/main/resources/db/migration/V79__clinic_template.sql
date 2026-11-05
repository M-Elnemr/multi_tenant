-- The look of a clinic's website: one of six designs, each with its own composition (hero, how doctors and services are presented).
-- 'clear' is the default; the list must match ClinicSettingsService.TEMPLATES and web/components/clinic/templates.ts.
ALTER TABLE medical.clinic_profiles ADD COLUMN template VARCHAR(16) NOT NULL DEFAULT 'clear'
    CONSTRAINT clinic_profiles_template_check CHECK (template IN ('clear', 'luxe', 'persona', 'dental', 'derma', 'center'));
