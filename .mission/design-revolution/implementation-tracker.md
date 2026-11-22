# Implementation tracker

## Phase 0 — contracts (lead)
- Theme token contract (`web/lib/theme.ts`: tokens, hex sanitising, contrast check, brand-derived palette) — COMMITTED bcd88b2 (smoke-tested with node; no unit test yet)
- `site_theme` nullable JSONB column on the three profile tables (V113) — COMMITTED bcd88b2, not yet run against a database
- Backend validation/endpoints for site_theme — IN PROGRESS (WS-J)
- Template id list (25 ids chosen, ≤16 chars) — COMMITTED in design-directions.md only; DB CHECK widening migration, backend sets, rollback SQL — NOT STARTED (lead, at integration)
- Per-template folder skeleton — not created by the lead; each workstream creates its own folders

## Phases 1–4
Phase 1 workstreams launched 2026-11-22: see workstreams.md. Phase 2 integration, Phase 3 QA, Phase 4 release: NOT STARTED.
