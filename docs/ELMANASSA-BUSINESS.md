# Elmanassa (المنصة): business and product documentation

Written 2026-11-20 from the repository, the deployment notes and the work done up to that date. Where something is not deployed or not verified with a real account, it says so. Nothing here is a promise of a feature that does not exist in the code.

---

## 1. What Elmanassa is

Elmanassa is a multi-tenant SaaS platform for small businesses in Egypt and the Arabic-speaking world. One platform, one database, one API. A business signs up and gets its own website on its own address in under a minute (`<name>.elmanassa.shop`, or its own domain), a dashboard to run the business, mobile apps, and an **AI assistant that talks to its customers** on WhatsApp, Instagram, Facebook Messenger, Telegram, the website chat and other channels.

The core idea: a small business owner should not need staff to answer messages all day. The AI knows the shop's real products, prices, stock, hours and policies, answers in the customer's own dialect, takes the order or the appointment, and hands over to a human when it should.

Everything is Arabic first (right to left, Egyptian dialect understood) with English alongside.

### Business types on the platform

| Type | For | State |
|---|---|---|
| **STORE** | Shops that sell physical products (fashion, food, electronics, beauty, home) | Live in production |
| **CLINIC** | Doctors and clinics: appointments, patients, queue, prescriptions | Live in production |
| **LAWYER** | Lawyers and law firms: cases, hearings, clients, fees, a distinguished website | Live in production (no AI by the owner's decision) |
| **SERVICE** | Appointment-based service businesses (salons, barbers, plumbers, repair, cleaning, filter maintenance) | Built and tested on branch `service-p2`; **not deployed yet** |

---

## 2. Who uses it

| Person | What they use |
|---|---|
| **Business owner / staff** | The dashboard on the web (`/dashboard`) and the business mobile app (Flutter, "elmanassa-business"); push notifications; WhatsApp reports |
| **Shop customer** | The shop website (`<shop>.elmanassa.shop`), the chat widget on it, WhatsApp / Instagram / Messenger / Telegram conversations, the customer mobile app |
| **Patient** | The clinic site, the patient portal, the customer mobile app |
| **Law client** | The office site, session booking, a client portal and app |
| **Shopper on the marketplace** | `open.elmanassa.shop`, a marketplace across shops |
| **Platform team (us)** | Platform admin (`/admin`): tenants, plans, wallet, AI usage and health, audit, security, visitors, WhatsApp, notifications |

There is **no SMS or OTP anywhere** (a decision about cost and reliability): login is phone or email plus password. A new staff member or patient gets a one-time code from the business and uses it once to choose their own password. Google sign-in exists for shop customers.

---

## 3. How money works

### 3.1 Shops (and, once released, service businesses)
- The shop itself is **free forever**: no subscription, no card.
- Usage is paid with **points** from a prepaid wallet (1 point = 1 EGP). Rates (the single source on the web side is `web/lib/pricing.ts`; the backend charges the same):
  - AI conversation: **1 EGP per block of 10 AI replies** (replies 1-10 = 1 EGP, 11-20 = 2 EGP ...).
  - Outbound message to a customer (order confirmation, cart reminder, update, advertising message to an opted-in customer): **0.1 EGP**.
  - AI comment reply or the private message the AI sends a commenter: **0.1 EGP** (stamp replies are free).
  - **100 EGP of free credit** once for every new business.
- When the wallet is empty, the AI pauses and the owner is notified. Wallet balances and transaction history are never edited by hand.

### 3.2 Clinics and lawyers
- The first month is free; then **250 EGP per month** for website hosting (the yearly price is 10 months).

### 3.3 Platform fees of third parties
Platform fees (Meta, TikTok, X, Snap, payment providers) are separate and are never described as free unless the provider's own documentation says so. Elmanassa uses each platform directly, with **no paid middleman**.

---

## 4. The shop (STORE)

**Website.** Each shop shows its own logo, colour, cover and banners, not the platform's. Ten ready designs (original, atelier, street, grove, midnight, maison, feast, bloom, oud, souq) with a gallery that previews the shop's own products in each. Pages: home, products, categories, product detail with variants, cart, checkout, account, orders, tracking, branches, contact, blog, policy pages, a booking page when appointments are on. Per-shop sitemap and robots, product feeds (Google XML, Facebook CSV, JSON).

**Catalogue.** Category tree, products with variants (colour, size ...), per-variant price and stock per branch, images, compare-at price for offers, an Arabic-aware search that ignores diacritics and letter variants.

**Egypt checkout.** Cash on delivery, delivery price and time by governorate zone, area and landmark, second phone, minimum order, optional COD fee, pickup, vacation mode. COD control: new orders wait for a confirmation call, phone history with blocking, tracking numbers. Delivery, pickup or both is a shop setting.

**Operations.** Orders and returns, invoices (tax id, VAT), shipping labels and shipments with carriers, inventory per branch with purchasing, suppliers and transfers, stock counts, **offline POS** (terminals, cashiers, shifts, returns, sync that works without internet), coupons and offers engine, loyalty, customer segments, abandoned carts, reviews, reports (sales, products, profit, customers, operations).

**Marketing and growth.** Posts and content plan, ads and autopilot, an AI builder, experiments, segments and broadcasts across platforms, Meta and TikTok pixels, product feeds, an AI "Copilot" for the owner.

**Connections (hub).** One screen to connect: Facebook (Page, Instagram, WhatsApp, Shop catalog in one login), Telegram, X, TikTok, Snapchat, product feeds, marketplaces and other stores, shipping carriers.

**Leads and bookings.** The owner chooses what the AI does: take product orders (default), collect customers as leads (name first, phone after they are satisfied, "customer service will contact you"; leads are an audience for the shop's offers), and take appointments for services.

## 5. The clinic (CLINIC)

Doctor profile, services, weekly schedule, online booking, appointments, **waiting room and queue**, patients (one person, one login, many clinics, each clinic with its own record), medical notes, prescriptions (printable), lab orders and results, insurance, reminders. Six designs for the clinic site, a doctor directory, a patient portal and a mobile app.

## 6. The law office (LAWYER)

An office website (three designs: classic, modern, heritage) with profile, services and session booking; cases (status, result, court, circuit, opponent, steps, documents); one calendar for sessions, hearings and notes; fees and payments with client statements; reminders (push one hour before a hearing, an early reminder days ahead, an evening digest and tomorrow's agenda on the lawyer's WhatsApp); clients portal; team roles. **No AI** for lawyers (owner's decision).

## 7. The service business (SERVICE): built, not released

Everything below exists on branch `service-p2` with targeted tests; none of it is deployed.
- Its own tenant type, plan (free, paid with points), signup option, sign-up landing page `/services` (Arabic and English).
- A diary-first dashboard: today's appointments, requests waiting, earned today; day/week/month calendar with confirm, decline, move, cancel, done, no-show and manual bookings; Insights (service revenue kept apart from product revenue, no-show rate, bookings made by the assistant).
- **Services**: categories, cover and gallery photos, promotion price, duration, buffer, location (shop, customer's home, or both) with service area, per-service capacity, questions the customer must answer (appliance brand, problem ...), add-ons that lengthen and price the visit, instructions and cancellation policy.
- **Availability**: working hours with breaks, special days, vacations and days off, business capacity (customers served at once), minimum notice, horizon, slot step, pending-request expiry, customer change deadline, automatic or manual confirmation, no double booking under concurrent requests (database advisory lock), the tenant's own timezone.
- **Staff and resources** (optional): people with skills, their own hours, days off and capacity; the customer may ask for a person or the first free one is assigned.
- **AI booking**: the assistant offers only free times computed by the backend, asks the service's questions, offers add-ons and a home visit, books, moves and cancels (after checking the booking belongs to the customer), and can sell products in the same chat.
- Ten website designs for service businesses (Lumière, Barbershop, HomeFix, FixIt, Aqua, Sparkle, Garage, Pro, Slot, Bazaar).
- Platform admin tenant list filters by one or several business types including SERVICE.

## 8. The AI seller (the heart of the product)

### 8.1 What it does
Answers customers in their language and dialect, knows the catalogue by meaning (search by meaning and exact per-variant stock), recommends, handles price bargaining within the owner's maximum discount, collects order details, creates the order through the real checkout code, tracks the order, handles changes and cancellations, remembers the customer across conversations, reads photos and voice notes, replies to comments, and hands over to the owner.

### 8.2 How a reply is produced
customer message -> conversation, pause and wallet checks -> history window (24 messages) plus durable memory (order on file, saved details, what we remember about this customer) -> system prompt (stable part first for caching: persona, rules, playbook, max discount, shop facts, catalogue, lessons; then the dynamic part) -> model loop with tools -> a chain of **deterministic guards with no extra model call**: money guard (every price must exist in shop data), claims guard (no invented urgency, guarantees, brands), fact check (colours, sizes, stock, specs, open-box versus new, warranty), critical gate (orders said to be recorded when they are not, wrong denials, empty-message claims, replies that are only a closing question, a quoted message about another product), then a review pass when needed, and fallbacks. The reply is sent only if it passes; otherwise the shop's own data replaces the claim. The **45-second invariant**: an unsafe commercial reply is never sent because time ran out (the customer gets a polite "try again in a minute" instead).

### 8.3 Models
Primary **GLM** (`glm-5.3-flash` through Hive); per-request fallback **Qwen** (`qwen3.7-flash`, DashScope); DeepSeek is available as a selectable option; Qwen also reads photos and voice. Keys are environment variables, never in code or logs.

### 8.4 Billing and safety rules
- Each AI conversation block is charged through one code path inside the chat service (`chargeTurn`); an empty wallet pauses the AI.
- Manual takeover by the owner pauses the AI for that chat; a timed pause exists too.
- **Customer frustration handling**: the shop chooses one of three modes: the AI calms the customer and solves it (default), one fixed message and hand over, or silent hand over. Detection is by word rules over the last messages (Egyptian and standard Arabic, English, Arabizi); the hand-over is one atomic server-side update before any model call or charge, and the AI stays out until the owner resumes it.
- Public website chat limits: `app.chat.rate-limit.mode` = `off` (current default by the owner's decision), `abuse` (recommended: 100 messages per 10 minutes per chat, 1,000 per address, 4,000 per hour per shop, the same text 6 times in 2 minutes), or `strict`. Every public chat message is a paid model call, so `off` costs money under spam.
- Tenant isolation is enforced in every query; cross-shop probes with manipulated identifiers found 0 leaks in 30 tries (2026-11-20).

### 8.5 Observability
Reply timelines in logs, SQL usage tables (per reply, per model, tokens), Micrometer, optional Langfuse over OTLP, an admin AI health screen, and a reply trace table with per-event checks.

## 9. Channels and integrations

| Channel | Mechanism | Evidence level today |
|---|---|---|
| Website chat widget | Built in (text, photos, voice, buttons) | Live |
| WhatsApp | The owner's **own number** through Evolution API (no paid WhatsApp Cloud API unless the owner opts in) | Live |
| Facebook Messenger and Instagram | Meta login (one click), Page and Instagram messaging, comments, bridge services for some flows | Live |
| Meta Shop catalog | Auto-publish products to a Meta catalog | Deployed, mock-tested, **not verified on a real catalog** |
| Telegram Business | Bot and business messages | Deployed, mock-tested, **needs bot token** |
| X (Twitter) direct messages | OAuth 2.0 + PKCE, signed webhook | Deployed, mock-tested, **needs X API access** |
| TikTok Business Messaging | Official API, signed webhook, TikTok's 48 h / 10 message limits enforced | Deployed, mock-tested, **needs TikTok app approval and a Verified Business account**; EEA, Switzerland, UK unsupported |
| Snapchat | Official API: login, a product catalog and a scheduled feed for ads. **No customer chat exists in Snap's API**, and the screen says so | Deployed, mock-tested, **needs a Snap developer app** |
| Shipping carriers, other stores/marketplaces | Integrations program (Bosta-style carriers, store imports) | Deployed (earlier program) |

Connection states are explicit (not connected, authorization pending, connected, requires reauthorization, approval required, restricted by region, temporarily unavailable, error): authorizing is never presented as able to message.

## 10. The marketplace: Open Store (`open.elmanassa.shop`)
A cross-shop marketplace with search, categories, shop pages, product pages, cart and checkout across shops, orders, account and wishlist. It lists STORE businesses only. Mobile polish includes 44 px touch targets.

## 11. Platform admin
Tenants (search, filter by one or several business types, status, plan, pay requests, expiring subscriptions), tenant detail and plan changes, wallet and points, AI usage and cost per shop, AI configuration (models, effort, key pool), AI health, audit log, security, visitor analytics, taxonomy of categories, domains, WhatsApp, notifications.

## 12. Technology

| Part | Stack | Location |
|---|---|---|
| API | Spring Boot 4.1, Java 17, Gradle, Flyway (104 migrations, V1-V103 live), PostgreSQL 16, modular monolith | `backend/` |
| Web | Next.js 16 (platform site, every storefront, clinic and law-office site, all dashboards; RTL and LTR) | `web/` |
| Mobile | Flutter: customer app, business (provider) apps, shared `platform_core` | `mobile/` (Android bundles in `release/`) |
| Edge | Caddy with on-demand TLS behind a host nginx | `infra/caddy` |
| Bridges | Facebook and Instagram bridge services, WhatsApp via Evolution API | `infra/` |
| CI/CD | GitHub Actions: backend (tests + image to ghcr.io), web (lint, typecheck, image), bridges, Caddy, mobile | `.github/workflows` |

**Multi-tenancy.** Every business is a row in `core.tenants` with a type; the host name (`<slug>.<root-domain>` or a custom domain) resolves the tenant. The web proxy routes by type to `t-store`, `t-clinic`, `t-lawyer` or `t-open`. Every query is tenant-scoped; roles and permissions are per membership (owner roles per type). Wallet, billing and subscription logic is shared.

**Code layout (backend packages).** `core` (tenants, auth, onboarding, RBAC, users), `commerce` (catalog, checkout, orders, inventory, POS, offers, shipping, segments), `chat` (the AI, channels, inbox, follow-ups, comments), `booking` (appointments for shops and service businesses), `medical`, `legal`, `billing` (plans, wallet, pay requests), `integrations` (Meta, carriers, marketplaces, Snapchat), `marketing`, `copilot`, `notifications`, `analytics`, `open` (marketplace), `platform` (admin), `files`, `audit`, `shared`.

**Production.** One server (`scanner-vps`, `/opt/multitenant`) running Docker Compose with `docker-compose.yml` **and** `docker-compose.test-slim.yml` together (local-disk uploads, Redis off, no MinIO). Containers: backend, web, db (postgres 16), edge (Caddy), evolution, fb-bridge, ig-bridge. Images come from `ghcr.io/m-elnemr/multi_tenant-*`. Deploying always passes both compose files, then checks health, the storage environment and a smoke test. Rollback images are tagged `rollback-<sha>` before every deploy; a verified database dump (`pg_restore -l` entry count) is taken first.

## 13. Quality program

- **Test suite**: about 1,000 backend integration tests (about 47 minutes on a developer machine), web lint, typecheck and an i18n key check. A full suite is mandatory before every production deploy; targeted tests after each change.
- **AI certification**: ten release gates (open-box versus new, minimum order, option and price locks, quotes beyond 28 messages, cross-shop isolation, malformed Arabic ...), ten customer personas over three demo shops, long memory conversations (5 to 100 messages), a 173-turn open-box campaign, wallet reconciliation and a benchmark. History of latency: P50 7-8 s, P90 15-19 s, P95 20-27 s.
- **Issue register** (`.mission/issue-register.md`) for every defect; defects are batch-fixed after a campaign and verified again on the deployed build.
- Demo shops used for live tests: `nile-style` (fashion), `beit-elshawarma` (restaurant), `tech-zone` (electronics), plus a demo lawyer.

## 14. State on 2026-11-20

| Area | State |
|---|---|
| Production | commit `f4a1f61`: shops, clinics, lawyers, AI with frustration handling, leads, bookings, TikTok/Snapchat/Telegram/X/Meta-catalog code, chat limit modes |
| Release 1 candidate | branch `release-1`: open-box fixes for warranty-in-fallback and quoted-product; waits for the live test campaign, batch fixes, one full suite, CI and deploy |
| SERVICE | branch `service-p2`, migrations V104-V107; waits for Release 1, then its own suite, deploy and visual and real-model checks |
| Design revamp (10 lawyer designs, 10 small-catalog shop designs, admin activity filters) | not started |
| Real-account verification of TikTok, Snapchat, Telegram, X, Meta catalog | blocked on the owner's credentials and each platform's approval |

Plan, estimates and risks: `.mission/master-plan.md`.

## 15. Operating notes and configuration

- Environment variables (names only): `GLM_API_KEY`, `QWEN_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_WEBHOOK_SECRET`, `X_CLIENT_ID`, `X_CLIENT_SECRET`, `X_BEARER_TOKEN`, `TIKTOK_APP_ID`, `TIKTOK_APP_SECRET`, `TIKTOK_AUTH_URL`, `SNAPCHAT_CLIENT_ID`, `SNAPCHAT_CLIENT_SECRET`, `PLATFORM_ROOT_DOMAIN`, `STORAGE_TYPE`, `REDIS_ENABLED`; database settings via `DB_*`.
- Public chat limits: set `app.chat.rate-limit.mode=abuse` to protect points from spam; default is off.
- Backups: the database dump before each deploy is stored under `/root/backups` on the server; at least one scheduled backup job is the owner's to add.
- Local run: see `README.md`; tests need a PostgreSQL database (`platform_test`; use `DB_NAME` to run two suites at once).
- More documents in `docs/`: `DEPLOY.md`, `ai-architecture.md`, `SCALE.md`, `LIMITS.md`, `PAYMENTS-SECURITY.md`, `GOOGLE-AND-PUSH.md`, `PLAY-STORE.md`, `CONTROL-PANEL-AR.md`.

## 16. Known limits and honest caveats
- The AI is certified by scripted campaigns on three demo shops, not by real customer volume; open-box certification was still open at the time of writing.
- Busy fallbacks (the polite "try again in a minute") appeared in about 3 of 45 gate conversations when several scripts ran at once; capacity under load is being measured.
- TikTok, Snapchat, Telegram, X and the Meta catalog have only been tested against mocks of the documented APIs.
- Snapchat offers no customer chat API; Elmanassa does not pretend otherwise.
- Staff scheduling for SERVICE is per person with capacity; resource types other than people (rooms, chairs) are modelled as business capacity.
- Facts about the company itself (legal entity, team, pricing contracts, support process) are not recorded in the repository and are therefore not in this document.

## 17. Glossary
- **Tenant**: one business on the platform. **Slug**: its name in the address.
- **Points / wallet**: prepaid credit, 1 point = 1 EGP.
- **Guard**: a code check that fixes or replaces an unsafe AI reply without another model call.
- **Open-box**: a product sold with an opened box, a separate product with its own price, stock and warranty.
- **Gate**: a release test with pass or fail criteria the AI must meet before a release.

---
## Affiliate (marketer) program (added 2026-11-21)

**What it is.** Each shop can run its own marketer program. A marketer promotes the shop with a personal link; the shop pays a commission only on orders that really arrive. The platform records everything and never holds or moves money.

**Marketers.** Own account (phone + password) at `/marketer`, separate from staff, patient and client sign-in. They browse a marketplace of shops that opted in, apply per shop, copy a link with an optional campaign tag, follow clicks, orders and commissions, ask the shop to pay their approved balance, rate shops, chat with them, open disputes and get notifications.

**Shops** (dashboard → Marketing → Marketers). Settings: commission %, how long a link is remembered (default 30 days), hold after delivery (default 14 days), minimum payout, marketplace listing, auto-approve. They approve / reject / block marketers, set individual rates and time-limited offers, answer payout requests (pay the marketer themselves, then record the proof), chat, rate and settle disputes.

**Money rules.** Commission = products after discounts (shipping excluded) × the rate that applied when the order was placed (frozen on the commission). A commission is PENDING, becomes APPROVED after the order arrived and the hold passed, is REVERSED if the order is cancelled / returned / refunded, and PAID when the shop records the payment with proof. A payout request locks the approved commissions it covers; a reversal shrinks it. Every change is an append-only ledger line. Nobody earns on their own order.

**Platform admin** (`/admin/affiliate`). Overview totals, switches (registration, marketplace, rate cap), marketers, shops, payout monitoring (with the age of each request), disputes, fraud flags (self-referral, duplicate customer, burst, shared address, high reversal, click flood) with review actions, and a searchable audit trail.

**Assumptions that can be changed later** (all in code constants or settings): shops stay free; no platform fee; no gateway; a rating needs one approved or paid commission; at most one open payout request per marketer and shop; fraud thresholds in `AffiliateFraud`; the rate cap is a platform setting (default 50%).
