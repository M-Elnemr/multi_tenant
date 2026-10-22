#!/usr/bin/env bash
# Makes a not-yet-certified store/clinic address unreachable instead of showing a "dangerous website" warning.
# Rewrites /etc/nginx/conf.d/multitenant-<root>-https.conf so that:
#   - only the names the certificate really covers are served (exact server_name list), and
#   - any other *.<root> name gets its TLS handshake rejected (ssl_reject_handshake): the browser says "can't connect", never "unsafe".
# A new address starts working within a minute: test-https-sync.sh adds it to the certificate and calls this script again.
# Scope: only names under <root>; every other site on this nginx is untouched. Does nothing when the conf file does not exist.
set -euo pipefail
root=${1:?usage: test-https-render.sh <root-domain>}
conf="/etc/nginx/conf.d/multitenant-${root//./-}-https.conf"
[ -f "$conf" ] || exit 0
names=$(openssl x509 -in "/etc/letsencrypt/live/$root/cert.pem" -noout -ext subjectAltName | tr -d ' ' | tr ',' '\n' | sed -n 's/^\(X509v3SubjectAlternativeName:\)\?DNS://p' | sort -u | tr '\n' ' ')
[ -n "$names" ] || exit 0
tmp=$(mktemp); cp "$conf" "$tmp.bak"
cat > "$tmp" <<CONF
# managed by test-https-render.sh - do not edit by hand. Served: the names on the certificate. Everything else under *.$root: handshake rejected.
server {
    listen 443 ssl;
    server_name $names;
    ssl_certificate /etc/letsencrypt/live/$root/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$root/privkey.pem;
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
server {
    listen 443 ssl;
    server_name *.$root;
    ssl_reject_handshake on;
}
CONF
if cmp -s "$tmp" "$conf"; then rm -f "$tmp" "$tmp.bak"; exit 0; fi
cp "$tmp" "$conf"
if nginx -t 2>/dev/null; then nginx -s reload; echo "https names now served: $names"; else cp "$tmp.bak" "$conf"; echo "nginx test failed, previous config restored" >&2; rm -f "$tmp" "$tmp.bak"; exit 1; fi
rm -f "$tmp" "$tmp.bak"
