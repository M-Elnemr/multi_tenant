# Architecture audit (2026-11-22, read-only)

## Templates today: 46
| Type | Count | Backend set | Frontend |
|---|---|---|---|
| Shop | 10 | StoreSettingsService.TEMPLATES | web/components/shop/templates/{ids,registry}.ts |
| Small shop (1–3 products) | 10 | SMALL_CATALOG_TEMPLATES (max 3 ACTIVE products, else SMALL_CATALOG_ONLY) | small-looks.ts |
| SERVICE | 10 | SERVICE_TEMPLATES | service-looks.ts |
| Clinic | 6 | ClinicSettingsService.TEMPLATES | web/components/clinic/templates.ts |
| Lawyer | 10 | LegalProfileService (inline, ~line 89) | web/lib/lawyer-templates.ts |

Storage: `template` VARCHAR(16) + CHECK on commerce.store_profiles (V42, widened V78/V106/V109), medical.clinic_profiles (V79), legal.profiles (V110). New ids must be ≤16 chars and need a CHECK-widening migration. Section order lives in `site_layout` JSONB (V26/V71).

## Rendering
web/proxy.ts rewrites by host → t-store (STORE+SERVICE), t-clinic, t-lawyer, t-open, t-platform. SSR with no-store fetches. Look chosen in lib/shop-look.ts; preview via `?tpl=&preview=1` (lib/site-template.ts, headers x-site-tpl/x-site-preview).

## Colors
Only core.branding primary/secondary (PATCH /api/v1/tenant/branding, hex regex, settings.manage), applied as --brand/--brand-2 in app/layout.tsx; readableBrand() in lib/color.ts. No token model, no logo extraction, no undo/redo. Palettes per template are hard-coded CSS.

## Other
- i18n: lib/messages.ts (en/ar), check-i18n fails lint on missing keys. Motion ^12.43. Fonts via next/font/google.
- Open Store: t-open routes (home, search, c, shops, shop, p, cart, checkout, orders, account, wishlist, login); backend com.platform.open; STORE only, no booking/service.
- /services landing already exists (components/landing/service) → links /register?type=SERVICE.
- Admin: 16 flat sidebar pages. Dashboard: shared shop/SERVICE layout; clinic and lawyer have own.
- QA tools: .mission/vqa/shot.mjs (puppeteer-core). CI: backend.yml, web.yml (lint+typecheck only). Deploy: CI images → compose pull/up with both compose files. Backup: infra/scripts/backup.sh.
