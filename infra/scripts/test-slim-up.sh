#!/usr/bin/env bash
# Start the personal-test stack on a small shared server, refusing if the machine has no real headroom.
# Run from the repo directory on the server:  ./infra/scripts/test-slim-up.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
MIN_RAM_MB=${MIN_RAM_MB:-700}
MIN_DISK_MB=${MIN_DISK_MB:-4000}
COMPOSE=(docker compose -p multitenant -f docker-compose.yml -f docker-compose.test-slim.yml)

avail_ram=$(free -m | awk '/^Mem:/ {print $7}')
avail_disk=$(df -Pm . | awk 'NR==2 {print $4}')
echo "== before: RAM available ${avail_ram} MB, disk available ${avail_disk} MB"
free -h; echo; docker ps --format 'table {{.Names}}\t{{.Status}}'
[ -f .env ] || { echo ".env is missing (copy .env.example and fill it in)"; exit 1; }
if [ "$avail_ram" -lt "$MIN_RAM_MB" ] && [ "${FORCE:-}" != "1" ]; then echo "Only ${avail_ram} MB RAM available (< ${MIN_RAM_MB}). Not starting. FORCE=1 overrides."; exit 2; fi
if [ "$avail_disk" -lt "$MIN_DISK_MB" ]; then echo "Only ${avail_disk} MB disk available (< ${MIN_DISK_MB}). Not starting."; exit 2; fi

"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d --remove-orphans

echo "== waiting for the backend to become healthy"
for i in $(seq 1 60); do
  if "${COMPOSE[@]}" ps backend --format '{{.Health}}' 2>/dev/null | grep -q healthy; then ok=1; break; fi
  sleep 5
done
[ "${ok:-}" = "1" ] || { echo "Backend did not become healthy in 5 minutes:"; "${COMPOSE[@]}" logs --tail 40 backend; exit 3; }

port=${TEST_EDGE_PORT:-8081}
root=${PLATFORM_ROOT_DOMAIN:-$(grep -E '^PLATFORM_ROOT_DOMAIN=' .env | cut -d= -f2-)}
echo "== smoke test through the edge on :$port"
curl -fsS -H "Host: $root" "http://127.0.0.1:$port/" -o /dev/null -w "platform home: %{http_code}\n"
curl -fsS -H "Host: $root" "http://127.0.0.1:$port/api/v1/tenant/resolve" ; echo
echo "== after"
free -h; docker ps --format 'table {{.Names}}\t{{.Status}}'; docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
echo "Open: http://$root:$port   (store/clinic sites: http://<name>.$root:$port)"
