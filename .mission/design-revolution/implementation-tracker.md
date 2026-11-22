# Implementation tracker

## Phase 0 — contracts (lead)
- [x] Theme token contract (--c-primary, --c-secondary, --c-accent, --c-bg, --c-bg-2, --c-surface, --c-text, --c-text-2, --c-btn-bg, --c-btn-text, --c-border) — COMMITTED bcd88b2 (theme.ts + V113 only; backend validation + id CHECK widening pending: WS-J / lead)
- [x] site_theme JSONB migration (nullable) on store_profiles, clinic_profiles, legal.profiles + validation (hex, token whitelist, contrast) — COMMITTED bcd88b2 (theme.ts + V113 only; backend validation + id CHECK widening pending: WS-J / lead)
- [x] Template id scheme (≤16 chars, active/retired flag, CHECK-widening migration + rollback SQL) — COMMITTED bcd88b2 (theme.ts + V113 only; backend validation + id CHECK widening pending: WS-J / lead)
- [x] Per-template folder skeleton + registry hooks — COMMITTED bcd88b2 (theme.ts + V113 only; backend validation + id CHECK widening pending: WS-J / lead)

## Phases 1–4
See workstreams.md. Phase 2 integration, Phase 3 QA, Phase 4 release: NOT STARTED.
