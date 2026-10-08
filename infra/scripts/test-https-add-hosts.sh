#!/usr/bin/env bash
# Adds store/clinic hostnames to the test certificate (Let's Encrypt cannot issue a wildcard without DNS control, so each name is a SAN).
# Run ON THE SERVER as root:  ./infra/scripts/test-https-add-hosts.sh <root-domain> shop1.<root> clinic1.<root>
# With no host arguments it adds every store/clinic host found in the test database.
set -euo pipefail
root=${1:?usage: test-https-add-hosts.sh <root-domain> [host ...]}; shift || true
hosts=("$@")
if [ ${#hosts[@]} -eq 0 ]; then
  mapfile -t hosts < <(docker exec multitenant-db-1 psql -U "${DB_USERNAME:-platform}" -d "${DB_NAME:-platform}" -tAc "select host from core.tenant_domains where host like '%.$root' order by host")
fi
current=$(certbot certificates --cert-name "$root" 2>/dev/null | sed -n 's/^ *Domains: //p')
args=(-d "$root"); for d in $current; do args+=(-d "$d"); done
new=0; for h in "${hosts[@]}"; do case " $current " in *" $h "*) ;; *) args+=(-d "$h"); new=1;; esac; done
[ "$new" = 1 ] || { echo "nothing to add (certificate already covers: $current)"; exit 0; }
certbot certonly --webroot -w /var/www/certbot --cert-name "$root" --expand "${args[@]}" --non-interactive --agree-tos
nginx -t && nginx -s reload
echo "certificate now covers: $(certbot certificates --cert-name "$root" | sed -n 's/^ *Domains: //p')"
