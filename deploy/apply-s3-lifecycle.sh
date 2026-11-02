#!/usr/bin/env bash
# Applies deploy/s3-lifecycle.json to a bucket and checks that the exports/ expiry is live (roadmap F6).
#   deploy/apply-s3-lifecycle.sh <bucket> [extra aws cli args, e.g. --profile prod --region eu-west-1]
# Needs the AWS CLI and s3:PutLifecycleConfiguration + s3:GetLifecycleConfiguration on the bucket.
set -euo pipefail

bucket="${1:?usage: $0 <bucket> [aws cli args]}"
shift
here="$(cd "$(dirname "$0")" && pwd)"

aws s3api put-bucket-lifecycle-configuration "$@" \
  --bucket "$bucket" \
  --lifecycle-configuration "file://$here/s3-lifecycle.json"

days="$(aws s3api get-bucket-lifecycle-configuration "$@" --bucket "$bucket" \
  --query "Rules[?Status=='Enabled' && Filter.Prefix=='exports/'].Expiration.Days | [0]" --output text)"

if [[ "$days" == "None" || -z "$days" ]]; then
  echo "FAILED: no enabled rule expiring exports/ on $bucket" >&2
  exit 1
fi
echo "OK: $bucket expires exports/ after $days days"
