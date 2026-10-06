# Known limits and next steps

- **Payments**: a mock provider sits behind the `PaymentProvider` interface. Plug in a real Egyptian gateway (create intent, hosted checkout URL, signed webhook) before taking card payments.
- **Object storage**: files use local disk (`FileStorage`). An S3/MinIO implementation behind the same interface is needed before scaling beyond one VPS. Malware scanning hook is a marked placeholder.
- **Rate limiting / login throttling** are in-memory (single node). Move to Redis when running more than one backend instance. Redis is not used yet (tenant resolution is cached in the web app).
- **Not built**: abandoned-cart reminders, waitlist, queue display, prescription PDF, patient export, page builder, platform directory, AI features, SMS/WhatsApp/push channels (interfaces exist), Postgres row-level security (application-level tenant scoping is enforced and tested).
- **Storage quota** (`max_storage_mb`) is not metered yet.
- **Mobile apps** are functional skeletons (tenant picker, login/first-time code, key flows). Booking and card payment are on web; mobile checkout is cash on delivery.
- **Production infrastructure** (Docker, Caddy on-demand TLS, GitHub workflows) is written but was not run in the authoring environment (no Docker, no GitHub remote).
- Legal/privacy review (Egypt PDPL for medical data, hosting region, processors) is required before launch.
