# Implementation tracker

## Phase 0 — contracts (lead)
- Theme token contract (`web/lib/theme.ts`: tokens, hex sanitising, contrast check, brand-derived palette) — COMMITTED bcd88b2 (smoke-tested with node; no unit test yet)
- `site_theme` nullable JSONB column on the three profile tables (V113) — COMMITTED bcd88b2, not yet run against a database
- Backend validation/endpoints for site_theme — IN PROGRESS (WS-J)
- Template id list (25 ids chosen, ≤16 chars) — COMMITTED in design-directions.md only; DB CHECK widening migration, backend sets, rollback SQL — NOT STARTED (lead, at integration)
- Per-template folder skeleton — not created by the lead; each workstream creates its own folders

## Phases 1–4
Phase 1 workstreams launched 2026-11-22: see workstreams.md. Phase 2 integration, Phase 3 QA, Phase 4 release: NOT STARTED.

## Integration status (2026-11-23, branch design-revolution-v2)
MERGED into design-revolution-v2 (tsc + eslint + check-i18n clean after merge): J theme (backend + SSR + editor + gallery + 25 palettes), A clinic cn-* x5, B lawyer ln-* x5 (only ln-global has own inner pages), E service vn-* x5, D small shop pn-* x5 (homes only; other pages re-skinned by tokens).
Backend: V113 site_theme, V114 template CHECKs + id sets for all 25 ids; rollback docs/rollback/V114_design_revolution_templates_rollback.sql. Focused tests passed (SiteTheme, ClinicIdentity, LawyerIntegration) via ./gradlew with DB_USERNAME=elnemr. Full suite NOT yet run.
NOT merged yet: C large shop (sn-market done, 4 pending), F Open Store, I /services landing (in progress), G dashboard, H admin (paused, partial).
Known gaps: clinic/lawyer/small inner pages share markup per kind; nothing run against the real backend or in production; no real-theme visual QA on the new templates yet.
