# Dashboard design system ("Quiet Precision, AI edition")

This file is the reference every dashboard screen is built against (shop, clinic and lawyer). The inbox (`components/inbox/*`, `app/t-store/dashboard/inbox/*`) is the most complete example of the style.

## Identity
- **Shop:** ink surfaces with violet→cyan AI accents (`linear-gradient(135deg,#7c5cff,#22d3ee)`). Use the gradient only on AI things: AI Assist buttons, the AI seller card, AI bubbles at about 10% opacity.
- **Clinic:** calm deep teal `#0f3d3a` with sage `#c4e0cf`, roomy spacing, soft paper cards, and no harsh reds for status.
- **Lawyer:** navy `#14213d` with brass `#b08d57` / `#d9b97f` on ivory `#fbf8f1`, plus a classical feel.
- **Per-role tokens:** the shell sets `data-role` and the tokens `--rail-*` and `--panel-*`. Use the accent tokens `var(--rail-accent)` and `var(--rail-accent-ink)` for small role-colored highlights.
- **Brand color:** `brand` / `brand-soft` is the business's own color. Use it for primary actions, active states and links.

## Tokens (Tailwind classes, all dark-mode aware)
- **Surfaces:** `bg-canvas` (page), `bg-surface` (cards and panels), `bg-surface-2`. Lines are `ring-1 ring-line` or `border-line`, strong ones `ring-line-strong`.
- **Text:** `text-ink`, `text-ink-2`, `text-muted`, `text-faint`.
- **Dark buttons:** use `bg-ink text-surface`, never `text-white` on `bg-ink`, because `ink` turns light in dark mode.
- **Status colors:** needs attention amber, waiting sky, problem rose/red, a person/handover violet, done emerald, closed slate. Badges use `bg-*-50 text-*-700/800 ring-1 ring-inset ring-*-600/15`.
- **Radius:** cards `rounded-[18px]`, inner blocks `rounded-[12px]`–`[14px]`, buttons `rounded-[10px]`–`[11px]`, chips `rounded-full`.
- **Type:** headings use `font-display`. Page title 22–26px, section title 15px semibold, body 13–14px, meta 11–12px. Numbers always get `tnum`.
- **Spacing:** page sections `space-y-5`, card padding `p-4`/`p-5`, list rows `px-4 py-3`.

## Building blocks (import, don't re-create)
- **`components/ui.tsx`:** `PageHeader` (eyebrow, title, subtitle, actions), `Button`, `Card`, `Section`/`Panel`, `Stat`, `KpiStrip`, `Tabs`/`Segmented`, `Table` (becomes stacked cards on phones), `Empty`, `Loading` (variant: list, table, cards, detail), `Badge`, `StatusBadge`, `Alert`, `Input`, `Select`, `Textarea`, `Field`, `Avatar`, the charts (`AreaChart`, `BarChart`, `DonutChart`, `Sparkline`, `ShareBars`), `Timeline`, `AttentionRow`, `Modal`, `Pager`.
- **`components/overlay.tsx`:** `Popover`, `Menu` (action menus), `Sheet` (side drawer on desktop, bottom sheet on phones), `Tooltip`, `ConfirmDialog` (destructive actions), `Switch`, `Checkbox`.
- **`components/toast.tsx`:** `useToast()(text, { tone, action })` for confirmations and errors after actions. The shell provides it.
- **`components/channel-icons.tsx`:** `ChannelIcon` and `ChannelDot` for WhatsApp, Messenger, Instagram and website.
- **`components/icons.tsx`:** `Icon`. Use only the names listed there; there are no emoji icons in the dashboard.
- **`components/dashboard/filters.tsx`:** `SearchBox`, `Chips`, `FilterGroup`, `ActiveFilters`.
- **`components/dashboard/section.tsx`:** `useSection(keys, fallback)` for `?tab=` parts and `useNavTitle(fallback)` for the page title from the menu.

## Page anatomy
1. `PageHeader`: an eyebrow (the section), a title (`useNavTitle`), a one-line subtitle saying what the page is for, and the primary actions on the end side.
2. An optional KPI row (`KpiStrip`, or 3–4 stat cards) when the numbers really help.
3. A toolbar: search, filter popover or chips, sort and view switch. Active filters show as removable chips.
4. Content: tables for lists (sticky header, row hover, row action `Menu`, skeleton rows while loading, `Empty` with a useful action), cards and grids for visual items, two columns on desktop for detail pages (main plus a side column of facts and actions).
5. Feedback: toasts after actions, `ConfirmDialog` before deletions, and inline `Alert` for real problems.

## Motion
- `motion/react`, already installed. Keep it functional and fast: fade/rise 160–260ms, `layout` for reorders and active pills, `AnimatePresence` for panels.
- Always respect `useReducedMotion()`. Never animate long lists item by item on every poll.

## Rules
- RTL first. Use logical properties only (`ms-`, `me-`, `ps-`, `pe-`, `start-`, `end-`, `text-start`), and add `flip-rtl` on direction icons.
- Phones (390px): no horizontal scroll, tables become cards, filters go in a `Sheet`, the primary actions stay reachable.
- **i18n:** every visible string goes through `t()`. New keys go into `lib/messages.ts` in both English and Arabic. On a worktree branch, add them to `web/i18n-additions/<area>.json` as `{"key": ["English", "عربي"]}` instead. Arabic is Egyptian-friendly but professional.
- Keep every API call, permission check, route, `?tab=` link and feature exactly as it is. This is a visual and UX redesign, not a behavior change.
- Never show fake data, and never promise a feature that doesn't exist.
