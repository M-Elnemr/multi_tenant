# Design Revolution — mission records

Branch: `design-revolution-v2` (from `main` 0eced8a). Started 2026-11-22.
Full plan: `~/.claude/plans/pasted-content-id-0f9b-elmana-reactive-blum-of-1ba4b5c6-7dee-4f87-a79f-23727d59297e.md`

## Decisions (user-confirmed 2026-11-22)
- Open Store: UI only. Service discovery in Open is a documented backend gap (API is STORE-only).
- Old templates: retired from the picker; ids stay valid in DB/render so published sites don't change.
- Delivery: one big release (parallel worktrees → integrate → QA → deploy once).

## Status values
NOT STARTED, IN PROGRESS, IMPLEMENTED, TESTED, COMMITTED, MERGED, DEPLOYED, PRODUCTION VERIFIED, BLOCKED.
Nothing is marked done on an agent's word alone; evidence goes in visual-qa.md / functional-qa.md.

## Files
- architecture-audit.md — what exists today
- workstreams.md — ownership and status per workstream
- implementation-tracker.md — feature-level status
- (to add as work lands) design-directions.md, route-inventory.md, visual-qa.md, functional-qa.md, deployment.md, blockers.md, final-completion-report.md

## Rules
- Follow web/components/dashboard/DESIGN.md and web/AGENTS.md. Logical properties only, every string via t() (en+ar), no fake data.
- Do not touch billing/chargeTurn/affiliate/AI settings.
- Shared files (messages.ts, registries, migrations, globals.css) are edited by the lead only; workstreams ship i18n as JSON additions.
