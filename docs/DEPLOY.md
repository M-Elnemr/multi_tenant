# Deploying

Two layouts. Both use the same images (`ghcr.io/m-elnemr/multi_tenant-*`, built by CI) and the same `docker-compose.yml`.

## A. Dedicated server (recommended for production)
1. Domain on Cloudflare. DNS: `A  @  -> server`, `A  *  -> server` (DNS only is fine; this layout also supports customers' own domains).
2. `cp .env.example .env`, fill it in (`PLATFORM_ROOT_DOMAIN`, `PLATFORM_EDGE_HOST`, `CF_API_TOKEN` with Zone:DNS:Edit, secrets).
3. `docker compose up -d`. Caddy takes 80/443 and gets one wildcard certificate.

## B. Shared server (another project already uses ports 80/443)
Goal: leave the other project untouched. Nothing here edits its nginx, containers, volumes or ports; remove ours with `docker compose -p multitenant down`.

Constraints you accept: the platform is reachable through **Cloudflare only** (orange cloud on, SSL mode *Full (strict)*) on origin port 8443, so tenants get their instant subdomains but **customers' own domains are not available** on this layout (they would need to reach port 443, which the other project owns). When you need them: give the platform its own small server (layout A), or route by SNI in the other project's nginx (a change to that project; do it only deliberately).

1. Use a domain that is **not** the other project's domain. Put it on Cloudflare. DNS: `A  @` and `A  *` -> server, **proxied**.
2. Check the server has headroom first: `free -h` (want about 3 GB available), `df -h /` (want 20 GB+). Otherwise use layout A on a new server.
3. `git clone` into its own directory (e.g. `/opt/multitenant`), `cp .env.example .env`, fill it in, and add:
   ```
   EDGE_HTTP_PORT=8081
   EDGE_HTTP_BIND=127.0.0.1
   EDGE_HTTPS_PORT=8443
   TRUSTED_PROXIES=<Cloudflare ranges from .env.example, checked against https://www.cloudflare.com/ips/>
   ```
4. Firewall: allow 8443 **only from Cloudflare ranges** (otherwise anyone can bypass Cloudflare), e.g. with ufw one `ufw allow from <range> to any port 8443 proto tcp` per range. Do not touch rules for 22/80/443.
5. Cloudflare: *Rules > Origin Rules*: for hostname `*.yourdomain` and `yourdomain`, set destination port **8443**. SSL/TLS mode: Full (strict). (Caddy holds a real Let's Encrypt certificate for `*.yourdomain` via the DNS challenge, so strict mode works.)
6. Start: `docker compose -p multitenant -f docker-compose.yml -f docker-compose.shared-host.yml up -d`.
7. Check the other project is unaffected: its site still answers, `docker ps` shows its containers with unchanged uptime, `ss -ltnp` shows 80/443 still owned by its nginx.

Rollback: `docker compose -p multitenant down` (add `-v` only if you also want the data gone).

## Storage image
MinIO's project no longer publishes images. Set `MINIO_IMAGE`/`MC_IMAGE` to an image you build or trust, or point the `S3_*` variables at any S3-compatible service and remove the `minio`/`minio-init` services.

## Smoke test after the first start
Register a store on the root domain, open `https://<slug>.<root>`, add a product with an image (it must load from `/media/...`), place a cash order; register a clinic, book and check in a patient.
