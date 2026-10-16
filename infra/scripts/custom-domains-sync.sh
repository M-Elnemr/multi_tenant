#!/usr/bin/env bash
# Gives every VERIFIED custom domain of a shop / clinic its own HTTPS certificate and its own nginx server block.
# Runs every minute from cron on the test server; does nothing when there is nothing to do.
#
#   custom-domains-sync.sh [--dry-run]     (--dry-run only prints what it would do)
#
# Per domain: waits until its DNS A record points at this server, issues a separate Let's Encrypt certificate (the elmanassa.shop
# certificate is never touched), writes /etc/nginx/conf.d/custom-<host>.conf, and reloads nginx only after `nginx -t` passes.
# A failed attempt waits 30 minutes (rate-limit safety). Domains removed from the platform lose their nginx file (the certificate stays).
#
# Install:  echo '* * * * * root /opt/multitenant/infra/scripts/custom-domains-sync.sh >> /var/log/multitenant-custom-domains.log 2>&1' > /etc/cron.d/multitenant-custom-domains
# Remove:   rm /etc/cron.d/multitenant-custom-domains /etc/nginx/conf.d/custom-*.conf && nginx -t && systemctl reload nginx
set -uo pipefail
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
SERVER_IP=${SERVER_IP:-216.158.233.36}
CONF_DIR=/etc/nginx/conf.d
STATE=/var/lib/multitenant-custom-domains
WEBROOT=/var/www/certbot
mkdir -p "$STATE"
exec 9>/var/lock/multitenant-custom-domains.lock
flock -n 9 || exit 0
log() { echo "$(date -Is) $*"; }

# global backoff after a failed nginx test, so a bad state is never retried every minute
if [ -f "$STATE/_nginx_fail" ] && [ $(( $(date +%s) - $(cat "$STATE/_nginx_fail") )) -lt 1800 ] && [ $DRY = 0 ]; then exit 0; fi

SQL="select d.host from core.tenant_domains d join core.tenants t on t.id = d.tenant_id where d.kind = 'CUSTOM' and d.is_verified and t.status not in ('ARCHIVED','CANCELLED') order by d.host"
hosts=$(echo "$SQL" | docker exec -i multitenant-db-1 sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB" -tA' 2>/dev/null) || { log "database not reachable, skipping"; exit 0; }

valid() { [[ "$1" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] && [[ "$1" != "elmanassa.shop" ]] && [[ "$1" != *.elmanassa.shop ]] && [[ "$1" != *sslip.io ]]; }
points_here() { dig +short +time=3 +tries=1 A "$1" @1.1.1.1 2>/dev/null | grep -qx "$SERVER_IP"; }
backed_off() { [ -f "$STATE/$1.backoff" ] && [ $(( $(date +%s) - $(cat "$STATE/$1.backoff") )) -lt 1800 ]; }
covers() { openssl x509 -in "/etc/letsencrypt/live/$1/fullchain.pem" -noout -ext subjectAltName 2>/dev/null | grep -q "DNS:$2\b"; }

render() { # host names
  cat <<CONF
# managed by custom-domains-sync.sh - do not edit by hand
server {
    listen 443 ssl;
    server_name $2;
    ssl_certificate /etc/letsencrypt/live/$1/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$1/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    client_max_body_size 20m;
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_read_timeout 60s;
    }
}
CONF
}

changed=0; backup=""
snapshot() { [ -n "$backup" ] || { backup="/var/backups/nginx-confd-$(date +%Y%m%d-%H%M%S).tgz"; tar czf "$backup" -C / etc/nginx/conf.d; }; }
declare -A wanted

for host in $hosts; do
  valid "$host" || { log "skip $host: not an acceptable domain name"; continue; }
  wanted[$host]=1
  conf="$CONF_DIR/custom-$host.conf"
  if [ ! -f "/etc/letsencrypt/live/$host/fullchain.pem" ]; then
    if backed_off "$host"; then continue; fi
    if ! points_here "$host"; then log "wait $host: its A record does not point to $SERVER_IP yet"; continue; fi
    args=(-d "$host"); points_here "www.$host" && args+=(-d "www.$host")
    if [ $DRY = 1 ]; then log "would issue a certificate for ${args[*]}"; continue; fi
    if out=$(certbot certonly --webroot -w "$WEBROOT" --cert-name "$host" "${args[@]}" --non-interactive --agree-tos 2>&1); then log "certificate issued for ${args[*]}"
    else date +%s > "$STATE/$host.backoff"; log "certificate FAILED for $host (retry in 30 min): $(echo "$out" | tail -3 | tr '\n' ' ')"; continue; fi
    rm -f "$STATE/$host.backoff"
  fi
  names="$host"; covers "$host" "www.$host" && names="$host www.$host"
  new=$(render "$host" "$names")
  if [ ! -f "$conf" ] || [ "$new" != "$(cat "$conf")" ]; then
    if [ $DRY = 1 ]; then log "would write $conf (server_name $names)"; changed=1; continue; fi
    snapshot; printf '%s\n' "$new" > "$conf"; changed=1; log "wrote $conf (server_name $names)"
  fi
done

# domains that are gone (removed, not verified any more, archived): drop their server block, keep the certificate
for f in "$CONF_DIR"/custom-*.conf; do
  [ -e "$f" ] || continue
  h=${f##*/custom-}; h=${h%.conf}
  if [ -z "${wanted[$h]:-}" ]; then
    if [ $DRY = 1 ]; then log "would remove $f"; changed=1; continue; fi
    snapshot; rm -f "$f"; changed=1; log "removed $f"
  fi
done

[ $changed = 1 ] || exit 0
[ $DRY = 1 ] && { log "dry run: nothing changed"; exit 0; }
if nginx -t >/dev/null 2>&1; then
  nginx -s reload && log "nginx reloaded"
else
  log "nginx -t FAILED, restoring the previous configuration: $(nginx -t 2>&1 | tail -3 | tr '\n' ' ')"
  rm -f "$CONF_DIR"/custom-*.conf; [ -n "$backup" ] && tar xzf "$backup" -C /
  date +%s > "$STATE/_nginx_fail"
  nginx -t >/dev/null 2>&1 && log "previous configuration restored"
fi
