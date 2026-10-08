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

## C. Personal test on a small shared server (what is used on 216.158.233.36)
For trying the platform yourself on a server that has about 1 GB of spare RAM and runs other projects. **Not for customers or real patient data**: plain HTTP, images on local disk, no Redis, no MinIO, small limits. Upgrade path: layout A or B above (new domain on Cloudflare, more RAM).

What it does: a separate compose project `multitenant`, containers capped in memory/CPU with a high `oom_score_adj` (if the server runs out of memory the kernel stops these first, never the other projects), and one tiny edge container on port **8081**. The other project's nginx, containers and ports are not touched.

On the server:
1. `git clone https://github.com/M-Elnemr/multi_tenant /opt/multitenant && cd /opt/multitenant`
2. Images: CI pushes `ghcr.io/m-elnemr/multi_tenant-backend|web`. Either make both packages public (GitHub > your profile > Packages > package > Settings > Change visibility) or `docker login ghcr.io` with a token that has `read:packages`. (Do not build on the server: the Gradle build needs more RAM than is spare.)
3. `./infra/scripts/test-slim-init.sh 216-158-233-36.sslip.io` creates `.env` with random secrets (shows the platform-owner login once).
4. `./infra/scripts/test-slim-up.sh` refuses to start without ~700 MB free RAM / 4 GB disk, starts the stack, waits for health and smoke-tests it.
5. Open `http://216-158-233-36.sslip.io:8081`. `sslip.io` resolves any `name.216-158-233-36.sslip.io` to the server, so a shop called `shop1` is `http://shop1.216-158-233-36.sslip.io:8081` (the app shows addresses without `:8081`; add it by hand).
6. Optional: to drop the `:8081`, add one extra nginx server block for `*.216-158-233-36.sslip.io` that proxies to `127.0.0.1:8081` (`nginx -t` first; rollback = delete the file and reload). It only matches those names; existing sites are unaffected.

Check the other project before and after: its site answers, `docker ps` shows its containers with the same uptime, `free -h`, `dmesg | grep -i oom`. Stop everything with `./infra/scripts/test-slim-down.sh` (add `--volumes` to delete the test data too).

### HTTPS for the personal test (same nginx + certbot pattern as the other project)
Phones often refuse or rewrite plain HTTP, so put TLS in front with the server's existing nginx. It uses its own name so the other project's certificate and config are untouched:
1. Root domain `mt.216-158-233-36.sslip.io` (stores are `<slug>.mt.216-158-233-36.sslip.io`). In `.env`: `PLATFORM_ROOT_DOMAIN`/`PLATFORM_EDGE_HOST` = that name, `TEST_SESSION_SECURE=true`, `TEST_EDGE_BIND=127.0.0.1` (the plain-HTTP edge is then reachable only from nginx).
2. `./infra/scripts/test-https-setup.sh mt.216-158-233-36.sslip.io` (as root): backs nginx up, adds two files in `/etc/nginx/conf.d`, issues the certificate, reloads, and removes its own files if anything fails.
3. Each new store/clinic needs its name in the certificate (no wildcard without DNS control): `./infra/scripts/test-https-add-hosts.sh mt.216-158-233-36.sslip.io` adds every host found in the database. `sslip.io` is not on the public suffix list, so Let's Encrypt's weekly limit is shared with all its users; if issuance is refused, wait or move to a real domain (layout A/B).

## Storage image
MinIO's project no longer publishes images. Set `MINIO_IMAGE`/`MC_IMAGE` to an image you build or trust, or point the `S3_*` variables at any S3-compatible service and remove the `minio`/`minio-init` services.

## Smoke test after the first start
Register a store on the root domain, open `https://<slug>.<root>`, add a product with an image (it must load from `/media/...`), place a cash order; register a clinic, book and check in a patient.
