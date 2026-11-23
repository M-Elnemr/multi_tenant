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

## Integration status update (2026-11-23, later)
ALL workstream branches now merged into design-revolution-v2: J, A, B, E, D, I (code only), G (2 small fixes only), H (partial), F, C (squashed; its 74 MB of screenshots kept out of the repo, copies in the session scratchpad).
Gates on the merged tree: `tsc` clean, `eslint --quiet` clean (after fixing an impure Date.now in admin PeriodBar), `check-i18n` ok (6726 keys per language), `next build` OK (after fixing Source Serif 4 weight option in ln-advisory). Full backend suite: running (log in the scratchpad backend-full.log).
Template count in code: clinic 5, lawyer 5, large shop 5, small shop 5, service 5 = 25, all registered, ids in V114 + backend sets.
HONEST STATUS BY AREA
- Templates: built; verified only on local mock backends with headless Chrome. Distinct inner pages: service (partly shared booking/contact), large shop (own cart/product/listing each), lawyer (only ln-global), clinic (shared inner pages, CSS skins), small shop (homes distinct; other pages token re-skin).
- Colour editor: working end to end for STORE (save, reload, isolation); clinic/lawyer editor and logo extraction not browser-tested; the old templates do not read the --c-* tokens (retired designs ignore theme by design).
- Open Store: restyled, UI only (service gap documented).
- Dashboard (G): NOT redesigned beyond 2 phone fixes. Admin (H): shell, overview, tenants, detail, wallet, audit, pay-requests, notifications; other admin pages only token-swapped, several never opened in a browser.
- /services landing: redesigned; illustrations are hand-built, not real UI screenshots.
NOT DONE: real-theme visual QA across the 25 templates, second pass on shared inner pages, full dashboard redesign, backup, deploy, production verification.
