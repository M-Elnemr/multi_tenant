# SERVICE business: audit of what exists (2026-11-20)

## Exists and is reusable (BookingService, V100, ServiceTools, /book page, Bookings dashboard)
- Tenant types: STORE, CLINIC, LAWYER (TenantType + V71 CHECK constraints on core.tenants and billing.subscription_plans). SERVICE does NOT exist yet.
- commerce.services: name, description, duration, buffer, price, active, sort. commerce.booking_settings: stations (shop-wide simultaneous capacity), slot step, min notice, horizon, auto_confirm. commerce.service_hours: several ranges per weekday (breaks). Closures by date. Tenant timezone honoured (core.tenants.timezone).
- Double-booking prevention: pg_advisory_xact_lock per tenant inside book(). Duration-aware slots. Confirm / decline / cancel (by customer or shop). AI tools: list_services, available_slots, book_appointment, my_bookings, cancel_booking, reschedule_booking (7 integration tests).
- Customer-facing /book page; dashboard Bookings screen; leads.

## Gaps against the addendum (to build, in phases)
1. Business type SERVICE: enum, V-migration of both CHECK constraints, plans (SERVICE_FREE/PRO), signup/onboarding/provisioner, dashboard routing + navigation (ScreenCatalog), template eligibility, admin filters.
2. Service model: categories, cover + gallery images, original/promo price, per-service capacity and hours, add-ons, location (shop / customer home), service area, custom required fields (configurable, not per-industry code), cancel/reschedule deadlines, related products/services.
3. Availability: special dates with different hours, vacation ranges (closures exist per date only), per-service capacity, pending-booking expiry, cancellation/reschedule deadlines.
4. Staff/resources (optional): table, skills, hours, capacity, assignment, any-available.
5. Dashboard: service-first home (today, pending, KPIs), calendar day/week/month with actions + manual appointment, mobile.
6. AI: service-aware goals/config, add-ons, home visit, product + service in one chat, ownership checks (exist for cancel/reschedule; extend to new fields).
7. Fulfilment mode: delivery / pickup / both for products.
8. 10 service templates; /services landing page; analytics (service vs product revenue, no-shows, AI-created).
9. Tests for all of the above.

## Plan: phases, each deployed only after a full suite
P1 SERVICE type + migrations + routing + admin filter (+ regression of STORE/CLINIC/LAWYER).
P2 service model v2 + availability v2 (+ tests).
P3 service dashboard + calendar.
P4 staff/resources.
P5 AI service tools v2 + fulfilment mode.
P6 /services landing + onboarding.
P7 10 templates.
