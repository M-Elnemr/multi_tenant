# Multi-tenant SaaS platform (Stores + Clinics)

Spec: `SaaS_MultiTenant_Ecommerce_Medical_Master_Spec.md`. Stack mirrors the scanner project:
Spring Boot 4.1 / Java 17 / Gradle / Flyway / PostgreSQL, Docker Compose, GitHub Actions -> ghcr.io.

## Status
Phase 0-1 backend: tenants, domains, onboarding, auth (phone + password, activation PIN, no OTP), RBAC, audit.
Next: Next.js web (onboarding wizard + tenant routing), then billing -> commerce -> medical.

## Add a store or doctor (one call)
`POST /api/v1/onboarding/tenants` `{type: STORE|CLINIC, name, slug, ownerFirstName, phone, password}`
-> tenant + owner + defaults, live immediately at `https://<slug>.<PLATFORM_ROOT_DOMAIN>` (TLS issued automatically).
Own domain: `POST /api/v1/tenant/domains {host}` -> add the returned TXT + CNAME records -> auto-verified -> `POST .../{id}/primary`.

## Staff / doctors / patients without SMS
`POST /api/v1/tenant/members` returns a one-time activation PIN; the person signs in with phone, enters the PIN
(`POST /api/v1/auth/activate`) and creates their own password.

## Local dev
```
createdb platform_dev platform_test      # local Postgres
cd backend && DB_USERNAME=$USER ./gradlew bootRun
DB_USERNAME=$USER ./gradlew test         # uses platform_test
```
Tenant hosts locally: `<slug>.platform.localtest.me` resolves to 127.0.0.1 (send `X-Forwarded-Host` when calling the API directly).

## Deploy (VPS)
Point `A <root>` and `A *.<root>` (and `edge.<root>`) at the server, copy `.env.example` to `.env`, then `docker compose up -d`.
Caddy (`infra/caddy/Caddyfile`) uses on-demand TLS gated by the backend's `/internal/domains/allowed`, so new tenants and custom
domains need no proxy changes.
