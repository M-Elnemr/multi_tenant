#!/usr/bin/env bash
# Stop and remove the personal-test stack. Containers and networks of OTHER projects are never touched (compose project "multitenant").
# Add --volumes to also delete the test database and uploaded files.
set -euo pipefail
cd "$(dirname "$0")/../.."
docker compose -p multitenant -f docker-compose.yml -f docker-compose.test-slim.yml down --remove-orphans "$@"
free -h
