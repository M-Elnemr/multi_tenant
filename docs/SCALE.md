# Running 1000 stores and clinics

## What was measured
`ScaleVolumeTest` builds one PostgreSQL database with **1002 tenants** (500 stores: 200 products, 100 orders each; 500 clinics: 200 patients, 500 appointments each),
about 1.3 million rows in total (340 MB), then drives the real application (host resolution, auth, permissions, queries) and records latency.
Reproduce: `dropdb --if-exists platform_scale; createdb platform_scale; cd backend && ./gradlew test -Pscale=true --tests '*ScaleVolumeTest'` (report: `backend/build/scale-report.md`).

Single client, milliseconds (p50 / p95):

| request | p50 | p95 |
|---|---|---|
| storefront product list (spread over 400 stores) | 9 | 11 |
| storefront product search | 5 | 13 |
| product page | 5 | 6 |
| clinic public site data / free slots for a day | 0-1 | 1 |
| store orders list / dashboard + cash to collect | 2 / 4 | 4 / 7 |
| clinic dashboard / waiting room / patient search / day's appointments | 0-3 | 1-4 |

Concurrent: **16 clients, 4000 mixed requests, 1192 requests/second, p50 5 ms, p95 31 ms, p99 145 ms.**
Every hot query was checked with `EXPLAIN`: all use tenant-leading indexes (no sequential scan on a large table), and tenants only ever see their own rows.

**Read these numbers honestly.** The test runs in-process (no network, no TLS, no Caddy/Next.js hop), against PostgreSQL on a developer laptop with a warm cache.
It proves the data model, indexes and queries scale to 1000 tenants and that nothing is accidentally O(all tenants). It does not replace a load test of the deployed stack
(Caddy + Next.js + backend + network) on the real VPS; run one (k6/wrk) against the staging server before launch.

## How the design scales
- **One database, `tenant_id` everywhere, every index leads with `tenant_id`.** Work per request depends on the tenant's own data, not on how many tenants exist.
- **Tenant lookup is cached** in each backend process (30 s; unknown hosts 5 s, so random Host headers cannot flood the database). Changes to domains/status invalidate it immediately on that instance.
- **Backend and web are stateless**: run N copies behind Caddy. Rate limits and login throttling live in Redis so every copy enforces the same limits (fail-open if Redis is down).
- **One wildcard certificate** (`*.root`, Cloudflare DNS challenge) covers every store/clinic subdomain; only customers' own domains use on-demand certificates, and only after the backend verifies them.
- **Images** are processed once at upload (EXIF/GPS removed, rotated upright, bounded to 2000 px, plus 800 px and 320 px variants) and served from the public bucket through the edge proxy with immutable keys: browsers and Cloudflare cache them forever and the app is not involved.
- **Medical files** live in a separate private bucket, are never reachable without an authorized API call, are versioned, and are streamed only after a permission check (and audited).
- **Plan quotas** meter storage per tenant (`max_storage_mb`), so no tenant can fill the disk.
- **Housekeeping**: a nightly job deletes sent e-mails, read notifications, old idempotency keys, webhook events, expired sessions and PINs. Business records and the audit log are kept.
- Per-statement and idle-in-transaction timeouts, bounded connection pool (`DB_POOL_SIZE`, default 20 per instance), Prometheus metrics at `/actuator/prometheus` (internal only), request/tenant/user ids on every log line.

## Sizing guidance (a starting point, validate on your hardware)
- Database: the seeded 1000-tenant data set is ~340 MB, i.e. a few GB with years of history. One 4 vCPU / 8 GB VPS runs PostgreSQL, 2 backend copies, 2 web copies, Redis and storage comfortably at this size.
- Postgres connections: `instances x DB_POOL_SIZE` must stay below `max_connections` (compose sets 200).
- Images: an average product picture is ~0.4 MB after processing (original + two variants). 500 stores x 200 products = ~40 GB. Plan quotas default to 1 GB (Starter) / 10 GB (Pro) per store; size the disk or bucket for the sum you intend to sell.
- Put Cloudflare in front (orange cloud) for CDN caching of `/media/*` and DDoS protection.

## Next steps beyond ~1000 tenants
Partition `audit.audit_logs` by month, move image processing to a background queue and uploads directly to the bucket (pre-signed PUT), add a PostgreSQL read replica for reports, introduce a search engine for catalogue search, and per-tenant request quotas at the edge.
