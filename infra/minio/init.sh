#!/bin/sh
# One-shot bucket setup for an S3-compatible server (MinIO). Safe to run repeatedly.
set -eu
mc alias set store "http://minio:9000" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

mc mb --ignore-existing "store/$S3_PUBLIC_BUCKET"
mc mb --ignore-existing "store/$S3_PRIVATE_BUCKET"

# Public bucket: anonymous read of objects only (no listing). It holds nothing but processed product images / logos.
mc anonymous set download "store/$S3_PUBLIC_BUCKET"
# Private bucket (medical files): never anonymous, versioned so an accidental overwrite/delete can be undone, objects encrypted at rest when KMS is configured.
mc anonymous set none "store/$S3_PRIVATE_BUCKET"
mc version enable "store/$S3_PRIVATE_BUCKET"
mc version enable "store/$S3_PUBLIC_BUCKET"

# Lifecycle: drop old non-current versions after 30 days (keeps the bucket from growing forever).
mc ilm rule add --noncurrent-expire-days 30 "store/$S3_PRIVATE_BUCKET" || true
mc ilm rule add --noncurrent-expire-days 7 "store/$S3_PUBLIC_BUCKET" || true
echo "buckets ready"
