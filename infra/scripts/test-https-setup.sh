#!/usr/bin/env bash
# HTTPS for the personal-test stack through the server's EXISTING nginx + certbot (same pattern as the other project on the box).
# Run ON THE SERVER as root:  ./infra/scripts/test-https-setup.sh mt.216-158-233-36.sslip.io
#  - adds two files in /etc/nginx/conf.d (port 80: certificate challenge + redirect, port 443: proxy to the test edge on 127.0.0.1:8081)
#  - issues ONE certificate named after the root domain; store/clinic hosts are added with test-https-add-hosts.sh
#  - never edits the existing sites; backs nginx up first; on any failure removes its own files and reloads, leaving nginx as it was.
set -euo pipefail
root=${1:?usage: test-https-setup.sh <root-domain>}
EDGE=${TEST_EDGE_PORT:-8081}
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
# one pair of files per root domain, so several domains can live side by side (the first root used the legacy names multitenant-test-*.conf)
slug_root=${root//./-}
HTTP=/etc/nginx/conf.d/multitenant-$slug_root-http.conf
HTTPS=/etc/nginx/conf.d/multitenant-$slug_root-https.conf
WEBROOT=/var/www/certbot
backup=/root/nginx-backup-$(date +%Y%m%d-%H%M%S).tgz
tar czf "$backup" /etc/nginx && echo "nginx backed up to $backup"
nginx -t
mkdir -p "$WEBROOT"

rollback() { echo "!! failed, restoring nginx to its previous state"; rm -f "$HTTP" "$HTTPS"; nginx -t && nginx -s reload; exit 1; }
trap rollback ERR

cat > "$HTTP" <<CONF
# Personal test (multi_tenant): certificate challenge and HTTP->HTTPS redirect, only for these names.
server {
    listen 80;
    server_name $root *.$root;
    location /.well-known/acme-challenge/ { root $WEBROOT; }
    location / { return 301 https://\$host\$request_uri; }
}
CONF
nginx -t && nginx -s reload

certbot certonly --webroot -w "$WEBROOT" --cert-name "$root" -d "$root" ${WWW:+-d "www.$root"} --non-interactive --agree-tos

cat > "$HTTPS" <<CONF
# Personal test (multi_tenant): TLS for $root and its stores/clinics, proxied to the test edge. One certificate (SANs added by test-https-add-hosts.sh).
server {
    listen 443 ssl;
    server_name $root *.$root;
    ssl_certificate /etc/letsencrypt/live/$root/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$root/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    client_max_body_size 20m;
    location / {
        proxy_pass http://127.0.0.1:$EDGE;
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
nginx -t && nginx -s reload
trap - ERR
echo "HTTPS is live: https://$root   (add store/clinic hosts: ./infra/scripts/test-https-add-hosts.sh $root <host> [<host>...])"
echo "Rollback: rm $HTTP $HTTPS && nginx -t && nginx -s reload"
