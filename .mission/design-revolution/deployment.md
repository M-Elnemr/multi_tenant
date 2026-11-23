# Deployment record (2026-11-23)

Deployed commit: backend built from 8c82495, web built from fc5ed92 (origin/main = fc5ed92).
Server: scanner-vps /opt/multitenant, compose project multitenant with BOTH files (docker-compose.yml + docker-compose.test-slim.yml).

## Evidence
- Local gates before push: tsc clean, eslint clean, check-i18n ok (6726 keys/language), next build ok, full backend suite 216 classes / 1103 tests / 0 failures / 5 skipped (gradle, 1h07m, DB_USERNAME=elnemr).
- CI: Backend (8c82495) success. Web first two runs FAILED in the docker `next build` step (Cinzel via next/font/google: "queries have exactly one entry"); fixed by self-hosting Cinzel (fc5ed92); Web CI (fc5ed92) success.
- DB backup (before deploy): /root/backups/db.pre-8c82495-20261123-0957.dump, 25,751,498 bytes, pg_restore -l lists 1502 entries. Restore NOT test-restored.
- Rollback images on the server: multitenant-backend:rollback-0eced8a (31ada1e00b98), multitenant-web:rollback-0eced8a (64db61ad5a0e).
- Deployed digests: backend sha256:5ac4f343460f6605d5dcbb46d45737dc61cc155817c0ab68b39077da56603f19 ; web sha256:6e49c622bb6c09899fc8c444659b2c81f367853c5338f6dbfc952075d25810f6 (`latest` verified identical to the commit-tagged images).
- Migrations: V113 (site_theme) and V114 (template CHECKs) applied, now v114, 0.2 s. Both additive/backward compatible.
- Storage env kept: STORAGE_TYPE=local, REDIS_ENABLED=false.
- Backend health: healthy after ~2 min (Started in 128 s on the 1-CPU host).

## Smoke tests (public, read-only)
200 on: elmanassa.shop /, /services, /tabib, /mohami, /register?type=SERVICE, /login; open.elmanassa.shop /, /search?q=a, /shops; beit-elshawarma and nile-style storefront home/products/cart. Existing shop still template=original, siteTheme=null (unchanged). Preview ?tpl=sn-market|sn-editorial|sn-bold|pn-flagship renders the new template markup. Logs: no new errors (one pre-existing UserDetailsService WARN).

## Not tested in production
Saving a colour theme, switching a real tenant to a new template, a real booking/order on a new template, admin pages (no admin session used), clinic/lawyer/service tenants (none inspected), theme editor logo extraction.

## Rollback
1. Images: on the server `P="docker compose -p multitenant -f docker-compose.yml -f docker-compose.test-slim.yml"`; `docker tag multitenant-backend:rollback-0eced8a ghcr.io/m-elnemr/multi_tenant-backend:latest` (same for web) then `$P up -d --no-deps backend web`.
2. DB: V113/V114 are additive; the old image ignores site_theme and the widened CHECKs. Only if a tenant has picked a new template id and the old image must run: docs/rollback/V114_design_revolution_templates_rollback.sql first. Full restore from the dump is the last resort.
