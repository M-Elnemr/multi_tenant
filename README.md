# Multi-tenant SaaS platform: Stores + Clinics

One platform, one database, one API. A business signs up and gets its own site on its own address in under a minute.
Spec: `SaaS_MultiTenant_Ecommerce_Medical_Master_Spec.md`.

| Part | Stack | Where |
|---|---|---|
| API | Spring Boot 4.1 / Java 17 / Gradle / Flyway / PostgreSQL (modular monolith) | `backend/` |
| Web | Next.js 16 (platform site, every storefront, every clinic site, all dashboards; Arabic RTL + English) | `web/` |
| Mobile | Flutter: `customer`, `provider`, `patient` apps + shared `platform_core` | `mobile/` |
| Edge | Caddy with on-demand TLS, Docker Compose, GitHub Actions to ghcr.io | `infra/`, `docker-compose.yml`, `.github/` |

## Add a store or a doctor
`/register` on the platform site (or `POST /api/v1/onboarding/tenants`): pick Store or Clinic, a name and an address.
The site is live immediately at `https://<slug>.<root-domain>` with defaults already in place (store: main branch, shipping, payment; clinic: doctor profile,
services, weekly schedule). Own domain: Dashboard -> Domains -> add -> create the two DNS records shown -> auto-verified, certificate issued automatically.

## No SMS / OTP anywhere
Login is phone (or email) + password. A new doctor, staff member or patient gets a **one-time code** from the business and uses it once to choose their own password.
A patient whose phone already has an account elsewhere claims their record with their password + the clinic's code. The patient code alone never logs anyone in.

## Run it locally
```
createdb platform_dev platform_test
cd backend && DB_USERNAME=$USER PLATFORM_ROOT_DOMAIN=localhost SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun     # seeds demo data
cd web && npm ci && API_BASE_URL=http://localhost:8080 npm run dev
```
Open `http://demo-store.localhost:3000`, `http://demo-clinic.localhost:3000`, `http://localhost:3000` (platform). Dev seed logins are printed at startup.
Card payments use a development mock gateway page (only with the `dev` profile).

Tests / checks:
```
cd backend && DB_USERNAME=$USER ./gradlew test                      # integration tests against real Postgres
cd web && npm run lint && npm run typecheck && npm run build       # lint also verifies every UI string exists in Arabic and English
cd mobile/packages/platform_core && flutter test; cd ../../apps/customer && flutter analyze
```

## Deploy (one VPS)
1. DNS: `A <root>`, `A *.<root>` and `A edge.<root>` -> the server.
2. `cp .env.example .env` and fill it in (never commit it). `docker compose up -d`.
3. Caddy issues certificates on demand, only for hosts the backend confirms (`/internal/domains/allowed`): new tenants and verified custom domains need no proxy change.
4. First platform owner: set `PLATFORM_ADMIN_PHONE` / `PLATFORM_ADMIN_PASSWORD` once.
Backups: daily `pg_dump` + the `app_uploads` volume, off-site and encrypted; test restores monthly (spec 65).

## Security model (short)
Tenant comes only from the Host header, never from client input. Every query is tenant-scoped and tested for cross-tenant access. Permission-based RBAC.
Medical files are private; patients see only data marked shared; every patient-record access is audited. Money in integer minor units.
Webhooks are signature-verified and idempotent. Rate limits, security headers, Argon2id, rotating refresh tokens.

## Not included yet / known limits
See `docs/LIMITS.md`.
