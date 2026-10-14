#!/bin/sh
# Creates the application bucket and a least-privilege MinIO user that can only touch that bucket.
# Idempotent: safe to run on every `docker compose up`.
set -eu

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "local/$STORAGE_BUCKET"

cat > /tmp/app-policy.json <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::$STORAGE_BUCKET"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::$STORAGE_BUCKET/*"]
    }
  ]
}
POLICY

mc admin policy create local erezer-app /tmp/app-policy.json >/dev/null 2>&1 \
  || mc admin policy create local erezer-app /tmp/app-policy.json
mc admin user add local "$STORAGE_ACCESS_KEY" "$STORAGE_SECRET_KEY"
mc admin policy attach local erezer-app --user "$STORAGE_ACCESS_KEY" >/dev/null 2>&1 || true
echo "MinIO bucket '$STORAGE_BUCKET' and user '$STORAGE_ACCESS_KEY' are ready."
