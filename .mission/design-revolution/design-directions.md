# Design directions — 25 new templates

Contract: ids are ≤16 chars (DB column VARCHAR(16)). Each template = its own folder, own header/hero/home/inner pages/footer/CSS, own default palette
expressed through the token contract in `web/lib/theme.ts` (`var(--c-*, <template default>)`). Shared code is allowed only for data, cart, booking and tokens.
Old ids stay valid for existing sites; the picker shows only these.

## Clinics (`cn-*`) — folder `web/components/clinic/templates/<id>/`
| id | Direction |
|---|---|
| cn-institute | Premium medical institution: established, trust, structured department grid |
| cn-boutique | Boutique specialist: editorial, serif, single-doctor focus |
| cn-medtech | Med-tech: precise, data-forward, cool palette, booking-first |
| cn-family | Family health centre: warm, rounded, reassuring, large touch targets |
| cn-atelier | Luxury specialist: spacious, restrained, image-led |

## Lawyers (`ln-*`) — `web/components/lawyer/templates/<id>/`
| id | Direction |
|---|---|
| ln-global | International firm: corporate, practice-area index |
| ln-boutique | Boutique practice: personal, partner-led |
| ln-advisory | Corporate advisory: structured, business blue/grey |
| ln-digital | Modern digital office: clean, consultation-first |
| ln-heritage | Heritage institution: editorial, timeless |

## Large shops (`sn-*`) — `web/components/shop/templates/large/<id>/`
| id | Direction |
|---|---|
| sn-editorial | Luxury editorial commerce |
| sn-market | Modern marketplace: discovery, facets, dense grid |
| sn-minimal | Minimal boutique |
| sn-bold | Bold contemporary retail |
| sn-lifestyle | Lifestyle brand: campaigns and collections |

## Small shops, 1–3 products (`pn-*`) — `web/components/shop/small/<id>/`
| id | Direction |
|---|---|
| pn-flagship | Single-product flagship |
| pn-showcase | Luxury product showcase |
| pn-trio | Three-product boutique (balanced composition for 1, 2 or 3) |
| pn-launch | Product launch / campaign |
| pn-brand | Minimal brand store |

## Service businesses (`vn-*`) — `web/components/shop/service/<id>/`
| id | Direction |
|---|---|
| vn-wellness | Luxury beauty and wellness |
| vn-homepro | Home services professional: request-a-service first |
| vn-repair | Repair and maintenance centre: category-led, technical |
| vn-studio | Appointment studio: calendar-led |
| vn-multi | Multi-service business: many categories |

Service rule: services are primary, products secondary. Slots and bookings come only from the real backend; no invented availability.
