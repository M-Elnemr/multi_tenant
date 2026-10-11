-- A sign-in ticket can now belong to a patient account (medical.patient_accounts) as well as a staff user, so user_id no longer references core.users only.
ALTER TABLE core.sso_tickets DROP CONSTRAINT IF EXISTS sso_tickets_user_id_fkey;
