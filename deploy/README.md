# Deployment configuration

Files here are applied to infrastructure, not built into the app.

| File | What | Roadmap |
|---|---|---|
| `clamd.conf` | clamd limits that cover a 2 GB upload. docker-compose mounts it; production must use the same values. | 2.2 / F3 |
| `s3-lifecycle.json` | Bucket lifecycle: expire `exports/` after 8 days (a backstop to the in-app 7-day expiry), move objects tagged `lock-tier=cold` to Glacier Instant Retrieval, abort stale multipart uploads. | 2.3 / F6, 3.3 |

## Apply the bucket lifecycle

AWS S3:

```bash
aws s3api put-bucket-lifecycle-configuration --bucket <bucket> --lifecycle-configuration file://deploy/s3-lifecycle.json
```

MinIO (no Glacier class: the cold-tier rule needs a configured remote tier; the exports rule works as is):

```bash
mc ilm rule add --prefix exports/ --expire-days 8 local/lock-media
```

`docker compose up` applies the exports rule to the local MinIO bucket automatically (`minio-init`).

## Production checklist (beta)

- `LOCK_SCAN_PROVIDER=clamav` in every `prod` deployment (F4). The `prod` profile refuses to start
  with the dev scanner, so a missing variable fails the deploy instead of skipping scans.
- clamd runs with `clamd.conf` above (F3), and an alert fires when the `lock.scan.pending` gauge keeps
  growing (scanner down or files over the limit).
- The bucket has object lock enabled if `LOCK_OBJECT_LOCK_YEARS` > 0, and the lifecycle above (F6).
- `LOCK_TRANSCODE_PROVIDER=ffmpeg` (the Docker image ships ffmpeg) for playback renditions (2.8).
- `LOCK_EMAIL_PROVIDER=postmark` + `LOCK_POSTMARK_TOKEN`, and `LOCK_PUSH_PROVIDER=expo` (2.6, 2.7).
  The `prod` profile refuses to start with the log-only email sender.
