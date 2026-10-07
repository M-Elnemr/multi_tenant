#!/bin/sh
# "A backup you have not restored is not a backup." Restores the latest dump into a throwaway database and checks the data is there.
set -eu
BACKUP_DIR="${BACKUP_DIR:-/var/backups/multi_tenant}"
LATEST="$(ls -1t "$BACKUP_DIR"/db/*.dump | head -n 1)"
echo "Restoring $LATEST into restore_test ..."
docker compose exec -T db psql -U "${DB_USERNAME:-platform}" -d postgres -c "DROP DATABASE IF EXISTS restore_test" -c "CREATE DATABASE restore_test"
docker compose exec -T db pg_restore -U "${DB_USERNAME:-platform}" -d restore_test --no-owner < "$LATEST"
TENANTS="$(docker compose exec -T db psql -U "${DB_USERNAME:-platform}" -d restore_test -tAc 'select count(*) from core.tenants')"
USERS="$(docker compose exec -T db psql -U "${DB_USERNAME:-platform}" -d restore_test -tAc 'select count(*) from core.users')"
docker compose exec -T db psql -U "${DB_USERNAME:-platform}" -d postgres -c "DROP DATABASE restore_test"
echo "OK: restored $TENANTS tenants and $USERS users from $LATEST"
