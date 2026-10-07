# Known limits and next steps

- **Payments**: a mock provider sits behind the `PaymentProvider` interface. Plug in a real Egyptian gateway (create intent, hosted checkout URL, signed webhook) before taking card payments.
- **Object storage**: S3-compatible storage is implemented and tested against a real S3 API server (moto); MinIO's own images/binaries are no longer distributed by the project, so choose the image/alternative (Garage, R2, AWS S3) before first deploy. Uploads still pass through the backend (validated and processed there; max 15 MB). Malware scanning hook is a marked placeholder.
- **Rate limiting / login throttling** use Redis when `REDIS_ENABLED=true` (tested against a Redis protocol server), in-memory otherwise.
- **Not built**: abandoned-cart reminders, waitlist, queue display, prescription PDF, patient export, page builder, platform directory, AI features, SMS/WhatsApp/push channels (interfaces exist), Postgres row-level security (application-level tenant scoping is enforced and tested).
- **Mobile apps** are functional skeletons (tenant picker, login/first-time code, key flows). Booking and card payment are on web; mobile checkout is cash on delivery.
- **Production infrastructure** (Docker, Caddy on-demand TLS, GitHub workflows) is written but was not run in the authoring environment (no Docker, no GitHub remote).
- Legal/privacy review (Egypt PDPL for medical data, hosting region, processors) is required before launch.
