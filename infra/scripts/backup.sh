#!/bin/sh
# Daily backup of the database and the uploaded files, encrypted, kept on the server and (once set up) copied off it.
#   cron (root):  15 3 * * * /opt/multitenant/infra/scripts/backup.sh >> /var/log/multitenant-backup.log 2>&1
#
# Encryption: gpg with the passphrase in $PASSFILE (created on the first run, mode 600). Keep a copy of that passphrase somewhere
# safe outside the server: without it the backups cannot be opened.
#
# Off-server copy: install rclone and define a remote called "offsite" (rclone config), then every run copies the new files to
# offsite:$OFFSITE_PATH. Examples:
#   Cloudflare R2:  rclone config create offsite s3 provider Cloudflare access_key_id=… secret_access_key=… endpoint=https://<account>.r2.cloudflarestorage.com
#   Backblaze B2:   rclone config create offsite b2 account=… key=…
#   or pull to a Mac each day:  rsync -a root@server:/var/backups/multitenant/ ~/multitenant-backups/
# Until then the run is recorded with offsite = false and the platform watchdog says there is no off-server copy yet.
set -eu
cd "$(dirname "$0")/../.."
BACKUP_DIR="${BACKUP_DIR:-/var/backups/multitenant}"
PASSFILE="${PASSFILE:-/root/.multitenant-backup-pass}"
OFFSITE_PATH="${OFFSITE_PATH:-multitenant-backups}"
if [ -f docker-compose.test-slim.yml ]; then P="docker compose -p multitenant -f docker-compose.yml -f docker-compose.test-slim.yml"; else P="docker compose"; fi
DBU="$(grep -E '^DB_USERNAME=' .env | cut -d= -f2- || true)"; DBU="${DBU:-platform}"
DBN="$(grep -E '^DB_NAME=' .env | cut -d= -f2- || true)"; DBN="${DBN:-platform}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"
chmod 700 "$BACKUP_DIR"
[ -s "$PASSFILE" ] || { umask 077; head -c 48 /dev/urandom | base64 > "$PASSFILE"; }

record() {   # kind ok size offsite detail
  $P exec -T db psql -q -U "$DBU" -d "$DBN" -v ON_ERROR_STOP=0 -c \
    "INSERT INTO ops.backup_runs (kind, ok, size_bytes, offsite, detail) VALUES ('$1', $2, $3, $4, '$(echo "$5" | tr -d "'" | cut -c1-400)')" >/dev/null 2>&1 || true
}

OUT="$BACKUP_DIR/daily/$STAMP.tar.gpg"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if ! $P exec -T db pg_dump -U "$DBU" -d "$DBN" -Fc > "$TMP/db.dump" || [ ! -s "$TMP/db.dump" ]; then
  record BACKUP false 0 false "pg_dump failed"; echo "backup $STAMP FAILED (database)"; exit 1
fi
# the uploaded pictures and files (the slim stack keeps them in a docker volume)
VOL="$(docker volume ls -q | grep -E '(^|_)uploads$' | head -n 1 || true)"
if [ -n "$VOL" ]; then docker run --rm -v "$VOL":/u:ro alpine tar -C /u -cf - . > "$TMP/uploads.tar"; fi
tar -C "$TMP" -cf - . | gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASSFILE" --symmetric --cipher-algo AES256 -o "$OUT"
SIZE="$(stat -c %s "$OUT" 2>/dev/null || stat -f %z "$OUT")"
# Sunday's copy is also kept as a weekly one
[ "$(date +%u)" = "7" ] && cp "$OUT" "$BACKUP_DIR/weekly/"
# keep 7 daily and 4 weekly copies
ls -1t "$BACKUP_DIR"/daily/*.tar.gpg 2>/dev/null | tail -n +8 | xargs -r rm -f
ls -1t "$BACKUP_DIR"/weekly/*.tar.gpg 2>/dev/null | tail -n +5 | xargs -r rm -f

OFF=false; NOTE="kept on the server"
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^offsite:'; then
  if rclone copy "$BACKUP_DIR" "offsite:$OFFSITE_PATH" --max-age 8d >/dev/null 2>&1; then OFF=true; NOTE="copied to offsite:$OFFSITE_PATH"; else NOTE="the off-server copy failed"; fi
fi
record BACKUP true "$SIZE" "$OFF" "$NOTE"
echo "backup $STAMP done: $SIZE bytes, $NOTE"
