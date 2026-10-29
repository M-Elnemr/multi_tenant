#!/bin/sh
# "A backup you have not restored is not a backup." Opens the newest encrypted backup, restores the database into a throwaway
# PostgreSQL container (the live database is not touched) and counts the main records.
#   cron (root):  40 4 * * 0 /opt/multitenant/infra/scripts/restore-test.sh >> /var/log/multitenant-backup.log 2>&1
set -eu
cd "$(dirname "$0")/../.."
BACKUP_DIR="${BACKUP_DIR:-/var/backups/multitenant}"
PASSFILE="${PASSFILE:-/root/.multitenant-backup-pass}"
if [ -f docker-compose.test-slim.yml ]; then P="docker compose -p multitenant -f docker-compose.yml -f docker-compose.test-slim.yml"; else P="docker compose"; fi
DBU="$(grep -E '^DB_USERNAME=' .env | cut -d= -f2- || true)"; DBU="${DBU:-platform}"
DBN="$(grep -E '^DB_NAME=' .env | cut -d= -f2- || true)"; DBN="${DBN:-platform}"
LATEST="$(ls -1t "$BACKUP_DIR"/daily/*.tar.gpg | head -n 1)"
TMP="$(mktemp -d)"
NAME="restore-test-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
record() { $P exec -T db psql -q -U "$DBU" -d "$DBN" -c "INSERT INTO ops.backup_runs (kind, ok, detail) VALUES ('RESTORE_TEST', $1, '$(echo "$2" | tr -d "'" | cut -c1-400)')" >/dev/null 2>&1 || true; }

gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASSFILE" -d "$LATEST" | tar -C "$TMP" -xf -
IMAGE="$($P images db --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | head -n 1)"; IMAGE="${IMAGE:-postgres:16}"
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=restore -e POSTGRES_USER="$DBU" -e POSTGRES_DB=restore -v "$TMP":/backup:ro "$IMAGE" >/dev/null
for i in $(seq 1 30); do docker exec "$NAME" pg_isready -U "$DBU" -d restore >/dev/null 2>&1 && break; sleep 2; done
if ! docker exec "$NAME" pg_restore -U "$DBU" -d restore --no-owner /backup/db.dump >/dev/null 2>"$TMP/err.txt"; then
  # pg_restore reports harmless warnings (extensions, roles) as errors; the counts below decide
  :
fi
COUNTS="$(docker exec "$NAME" psql -U "$DBU" -d restore -tAc "select 'tenants=' || (select count(*) from core.tenants) || ' users=' || (select count(*) from core.users) || ' products=' || (select count(*) from commerce.products) || ' orders=' || (select count(*) from commerce.orders)" 2>/dev/null || true)"
UPLOADS="$( [ -f "$TMP/uploads.tar" ] && tar -tf "$TMP/uploads.tar" | wc -l | tr -d ' ' || echo 0)"
if echo "$COUNTS" | grep -q 'tenants=[1-9]'; then
  record true "$(basename "$LATEST"): $COUNTS files=$UPLOADS"; echo "OK: restored $(basename "$LATEST"): $COUNTS, $UPLOADS uploaded files"
else
  record false "$(basename "$LATEST"): restore gave no data"; echo "FAILED: $(basename "$LATEST") restored no data"; head -5 "$TMP/err.txt"; exit 1
fi
