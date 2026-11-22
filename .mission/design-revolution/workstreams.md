# Workstreams

| WS | Scope | Owns | Status |
|---|---|---|---|
| J | Token engine, site_theme migration/API, SSR injection, color editor, gallery/picker | lib/theme*, template-picker, branding editor, backend theme service | IN PROGRESS (agent launched 2026-11-22) |
| A | 5 clinic templates | components/clinic/templates/* | IN PROGRESS (agent launched 2026-11-22) |
| B | 5 lawyer templates | components/lawyer/templates/* | IN PROGRESS (agent launched 2026-11-22) |
| C | 5 large-shop templates | components/shop/templates/large/* | IN PROGRESS (agent launched 2026-11-22) |
| D | 5 small-shop templates | components/shop/small/* | IN PROGRESS (agent launched 2026-11-22) |
| E | 5 service templates | components/shop/service/* | IN PROGRESS (agent launched 2026-11-22) |
| F | Open Store (UI only) | app/t-open, components/open | IN PROGRESS (agent launched 2026-11-22) |
| G | Merchant dashboard | components/dashboard, app/t-*/dashboard | IN PROGRESS (agent launched 2026-11-22) |
| H | Admin console | components/admin, admin pages | IN PROGRESS (agent launched 2026-11-22) |
| I | /services landing | components/landing/service | IN PROGRESS (agent launched 2026-11-22) |

Phase 0 contract (theme tokens, site_theme, template id scheme) must land first: see implementation-tracker.md.

## Run control (2026-11-23)
Quota is shared, so at most 3-4 agents run at once (user decision). Running: J (theme/editor), A (clinic cn-institute first), E (service vn-wellness first).
PAUSED (work kept in their worktrees, resumable by SendMessage to the same agent): B lawyer, C large shop, D small shop, F Open Store, G dashboard, H admin, I /services landing.
Order after A/E/J reach one working template each: next templates per category, then C/D/B, then F/G/H/I.
Worktrees: .claude/worktrees/agent-<id>; branches worktree-agent-<id>. Uncommitted work may exist in the paused ones: check `git status` there before relying on them.
