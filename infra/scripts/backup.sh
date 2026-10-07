#!/bin/sh
# Daily backup: PostgreSQL dump + a mirror of both buckets. Run from cron on the server:  0 3 * * * /opt/app/infra/scripts/backup.sh
# Ship $BACKUP_DIR off the server (rclone/restic to another provider) and encrypt it there; a backup on the same disk is not a backup.
set -eu
BACKUP_DIR="${BACKUP_DIR:-/var/backups/multi_tenant}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR/db" "$BACKUP_DIR/files"

docker compose exec -T db pg_dump -U "${DB_USERNAME:-platform}" -d "${DB_NAME:-platform}" -Fc > "$BACKUP_DIR/db/$STAMP.dump"
docker compose run --rm --entrypoint sh minio-init -c \
  "mc alias set store http://minio:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD && mc mirror --overwrite --remove store /backup" >/dev/null 2>&1 || \
  docker compose exec -T minio sh -c "tar -C /data -cf - ." | gzip > "$BACKUP_DIR/files/$STAMP.tar.gz"

# keep 14 daily database dumps and 7 file archives
ls -1t "$BACKUP_DIR"/db/*.dump 2>/dev/null | tail -n +15 | xargs -r rm -f
ls -1t "$BACKUP_DIR"/files/*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
echo "backup $STAMP done"
