#!/usr/bin/env bash
# Runs every minute from cron on the test server: adds new store/clinic hosts to the certificate so a freshly created site opens without a
# browser warning. Does nothing when there is nothing new. After a failed issuance (for example a Let's Encrypt rate limit) it waits 30 minutes
# before trying again, so it can never hammer the certificate authority.
# Install:  echo '* * * * * root /opt/multitenant/infra/scripts/test-https-sync.sh mt.216-158-233-36.sslip.io >> /var/log/multitenant-https-sync.log 2>&1' > /etc/cron.d/multitenant-https-sync
# Remove:   rm /etc/cron.d/multitenant-https-sync
set -uo pipefail
root=${1:?usage: test-https-sync.sh <root-domain>}
here="$(cd "$(dirname "$0")" && pwd)"
exec 9>/var/lock/multitenant-https-sync.lock
flock -n 9 || exit 0
backoff=/var/lib/multitenant-https-backoff
if [ -f "$backoff" ] && [ $(( $(date +%s) - $(cat "$backoff") )) -lt 1800 ]; then exit 0; fi
out=$("$here/test-https-add-hosts.sh" "$root" 2>&1); rc=$?
if [ $rc -ne 0 ]; then date +%s > "$backoff"; echo "$(date -Is) FAILED (retry in 30 min): $out" | tail -5; exit 0; fi
rm -f "$backoff"
case "$out" in *"nothing to add"*) ;; *) echo "$(date -Is) $out" | tail -3;; esac
