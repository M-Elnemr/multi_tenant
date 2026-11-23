# Open Store: the service gap

Status: note only. Nothing here is built. Written during design-revolution workstream F.

## What the Open Store is today

The Open Store (`open.elmanassa.shop`, `web/app/t-open`) is a product marketplace. Every route assumes a physical or digital good with variants, stock, a cart and cash-on-delivery checkout. The Open API (`/api/open/*`) only exposes products, categories, shops, cart quotes, checkout and orders. Shops of type SERVICE, CLINIC and LAWYER do not appear in it, and their offerings (services, doctors, lawyers, appointment slots) cannot be discovered or booked from the marketplace.

The product catalogue UI was redesigned in this workstream and is deliberately product-shaped: price, stock, rating, add to cart. A service does not fit that shape, so a service-aware marketplace needs new surfaces and new API, not a re-skin.

## What a service-aware Open Store would need

### 1. Backend: discovery
- `GET /api/open/services`: paged list of bookable services across SERVICE shops, with name, shop, category, duration, price (fixed, from, or on request), rating, city/governorate, mode (in person, at customer, online).
- `GET /api/open/services/facets`: category, price-from range, duration, mode, governorate, rating, "available today".
- `GET /api/open/services/{shopSlug}/{serviceSlug}`: detail with description, what is included, staff, policies (cancellation, deposit), reviews.
- Equivalent directory endpoints for clinics (`/doctors`, specialty facet) and lawyers (`/lawyers`, practice-area facet), reusing the existing doctor directory and lawyer profile data.
- `GET /api/open/search/suggest` extended so typeahead returns services, doctors and lawyers as separate result groups.

### 2. Backend: availability and booking
- `GET /api/open/services/{id}/availability?from&to&staff`: free slots per day, honouring working hours, exceptions, buffers and existing bookings (this logic already exists on tenant hosts; it must be exposed through a public, rate-limited, tenant-scoped read).
- `POST /api/open/bookings`: create a booking with idempotency key, customer identity (the existing Open shopper account or guest phone), slot, notes. Same guard rails as tenant booking: slot hold with expiry, double-booking prevention, no paid OTP (phone and password only).
- `GET /api/open/bookings` and `/{id}`: the shopper's bookings, with cancel and reschedule within each shop's policy.
- Money: services are mostly pay-at-visit; deposits or online payment must wait for the Paymob decision. Do not invent a payment flow in the marketplace.

### 3. Handoff to the tenant host
The booking engine, staff rules and notifications live on the tenant host. The marketplace should either call the same service layer (preferred, one source of truth) or hand off with a signed deep link to `https://<shop>.elmanassa.shop/book/<service>?slot=...` carrying the shopper's identity. A handoff-only first release is acceptable and much smaller: list services and send the shopper to the tenant booking page.

### 4. UI slots already prepared
- Header mega menu and tab bar: a fifth destination ("Services") can be added beside Shops without layout change; the mega menu already renders grouped sections.
- Search: results page needs a segmented control (Products, Services, Doctors, Lawyers) above the existing filters; `OpenListing` is the product list and should stay product-only.
- Shop profile (`/shop/[slug]`): the header block has room for a "Book" primary action and a Services tab beside Products; a SERVICE shop with no products should default to the Services tab.
- New components needed: service card (duration and price-from instead of cart), slot picker (day strip plus time chips, RTL aware, keyboard operable), booking summary and confirmation, "my bookings" in the account area.
- Empty states already use `OEmpty` and will work for "no slots available".

### 5. Data and moderation prerequisites
- Opt-in flag per shop and per service for appearing in the marketplace (shops currently opt in for products only).
- Category taxonomy for services (separate from product categories) and a review model tied to completed bookings.
- Clinics and lawyers carry regulatory sensitivity: publish only what the profile owner has marked public, and never show medical or case details in listings.

## Suggested order
1. Handoff-only services directory (read endpoints + service card + shop Services tab).
2. Availability read endpoint and slot picker on the marketplace, booking still completed on the tenant host.
3. Native booking in the marketplace with the shopper's bookings list.
4. Doctors and lawyers directories.
