#!/usr/bin/env bash
# Creates .env for the personal-test stack with fresh random secrets. Run ON THE SERVER so secrets never travel:
#   ./infra/scripts/test-slim-init.sh 216-158-233-36.sslip.io
# Prints the generated platform-owner login once. Refuses to overwrite an existing .env.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=${1:?usage: test-slim-init.sh <root-domain>, e.g. 216-158-233-36.sslip.io}
[ ! -e .env ] || { echo ".env already exists, not touching it"; exit 1; }
rnd() { openssl rand -hex "$1"; }
admin_pw="$(rnd 8)Aa1!"
umask 077
cat > .env <<ENV
DB_NAME=platform
DB_USERNAME=platform
DB_PASSWORD=$(rnd 16)
JWT_SECRET=$(openssl rand -base64 32)
PLATFORM_ROOT_DOMAIN=$root
PLATFORM_EDGE_HOST=$root
WEB_SESSION_SECRET=$(rnd 32)
BILLING_WEBHOOK_SECRET=$(rnd 32)
PLATFORM_ADMIN_PHONE=+201000000000
PLATFORM_ADMIN_PASSWORD=$admin_pw
CORS_ALLOWED_ORIGINS=
SPRING_PROFILES_ACTIVE=
# Required by the shared compose file but unused in the slim test profile:
ACME_EMAIL=test@example.com
CF_API_TOKEN=unused
REDIS_PASSWORD=$(rnd 12)
S3_ACCESS_KEY=unused
S3_SECRET_KEY=$(rnd 12)
ENV
echo "Created .env (mode 600)."
echo "Platform owner login: +201000000000 / $admin_pw   (shown once, stored only in .env)"
