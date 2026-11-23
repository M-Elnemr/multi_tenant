# Design revolution: completion report (2026-11-23)

DEPLOYED to production (backend 8c82495, web fc5ed92). PRODUCTION VERIFIED only at the level of smoke tests (see deployment.md). This is a partial delivery: see "Not done".

## Delivered and live
- 25 new templates registered and renderable: clinic cn-institute/boutique/medtech/family/atelier; lawyer ln-global/boutique/advisory/digital/heritage; large shop sn-market/editorial/minimal/bold/lifestyle; small shop pn-flagship/showcase/trio/launch/brand; service vn-wellness/homepro/repair/studio/multi. Old ids stay valid (published sites unchanged); picker lists active designs.
- Colour system: token contract (web/lib/theme.ts), per-site site_theme (V113) with strict backend validation, SSR injection, preview-only draft, 25 default palettes, editor (simple/advanced, logo suggestions, contrast guard, undo/redo, live preview) and a new template gallery.
- /services landing redesign, Open Store UI redesign (UI only), admin console shell/overview/tenants/detail/wallet/audit/pay-requests/notifications, two small merchant-dashboard phone fixes.
- V114 widened template CHECKs; rollback SQL in docs/rollback.

## Verification actually done
Local gates (tsc, eslint, i18n check, next build, full backend suite 1103 tests green), CI green, headless-Chrome screenshots and scripted interactions against MOCK backends per workstream (screenshots in the repo under .mission/design-revolution/shots for some, others in the session scratchpad), production smoke tests.

## Not done / honest gaps
- Merchant dashboard: not redesigned (2 phone fixes only). Admin: other pages only token-swapped, several never opened in a browser.
- Inner pages of clinic (all five), lawyer (four of five) and small-shop templates share markup/are token re-skins, not distinct designs.
- No template has been run against a real tenant; no real saved theme tested on the new templates; clinic/lawyer editor and logo extraction not browser-tested; old templates ignore the colour tokens by design.
- /services landing previews are illustrations, not real screenshots.
- Open Store service discovery: backend gap documented in open-store-service-gap.md.
- Full 25-template x 8 viewport x AR/EN visual QA was not done; coverage is partial per workstream reports.
- Backup restore not test-restored.
