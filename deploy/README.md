# Deployment configuration

Files here are applied to infrastructure, not built into the app.
The deployment shape is drawn in [`../docs/architecture.md`](../docs/architecture.md) §9.

| File | What | Roadmap |
|---|---|---|
| `clamd.conf` | clamd limits that cover a 2 GB upload. docker-compose mounts it; production must use the same values. | 2.2 / F3 |
| `production.env.example` | Every variable a `prod` deployment sets. Variables marked `[guarded]` stop the API from starting if missing or dev defaults. | F4 |
| `prometheus/lock-alerts.yml` | Prometheus alert rules (scanner down, scan backlog, exports lifecycle missing, outbox, seal lag, integrity). Unit tests in `lock-alerts.test.yml`. | 1.5 / F3, F6 |
| `apply-s3-lifecycle.sh` | Applies `s3-lifecycle.json` to a bucket and verifies the `exports/` rule is live. | F6 |
| `s3-lifecycle.json` | Bucket lifecycle: expire `exports/` after 8 days (a backstop to the in-app 7-day expiry), move objects tagged `lock-tier=cold` to Glacier Instant Retrieval, abort stale multipart uploads. | 2.3 / F6, 3.3 |

## Apply the bucket lifecycle

AWS S3 (applies the rules, then reads them back and fails if `exports/` is not covered):

```bash
deploy/apply-s3-lifecycle.sh <bucket> --profile <profile>
```

The API checks this itself at startup and every 6 hours (`LifecycleRuleCheck`): a missing or too-lax
rule logs a warning and sets the `lock_storage_exports_lifecycle` gauge to 0, which the
`LockExportLifecycleRuleMissing` alert pages on. Give the API's credentials
`s3:GetLifecycleConfiguration` on the bucket, or the gauge reads -1 (unknown).

MinIO (no Glacier class: the cold-tier rule needs a configured remote tier; the exports rule works as is):

```bash
mc ilm rule add --prefix exports/ --expire-days 8 local/lock-media
```

`docker compose up` applies the exports rule to the local MinIO bucket automatically (`minio-init`).

## Alerts

Point Prometheus at `/actuator/prometheus` on `LOCK_MANAGEMENT_PORT` and add the rules:

```yaml
# prometheus.yml
rule_files: [/etc/prometheus/lock-alerts.yml]
```

```bash
promtool check rules deploy/prometheus/lock-alerts.yml
promtool test rules deploy/prometheus/lock-alerts.test.yml
```

The malware-scan alerts (F3) use three gauges: `lock_scan_up` (clamd answers PING, checked every
minute), `lock_scan_oldest_age_seconds` (catches a scanner that is up but failing, for example on files
over `StreamMaxLength`) and `lock_scan_pending`. `AlertRulesTest` fails the build if a rule uses a
metric the API doesn't export.

## Production checklist (beta)

- Start from `production.env.example` (F4). `LOCK_SCAN_PROVIDER=clamav` is required: the `prod` profile
  refuses to start with the dev scanner, so a missing variable fails the deploy instead of skipping
  scans. `ProductionEnvTemplateTest` keeps the template in step with the guard.
- clamd runs with `clamd.conf` above (F3), and the alert rules above are loaded.
- The bucket has object lock enabled if `LOCK_OBJECT_LOCK_YEARS` > 0, and the lifecycle above (F6).
- `LOCK_TRANSCODE_PROVIDER=ffmpeg` (the Docker image ships ffmpeg) for playback renditions (2.8).
- `LOCK_EMAIL_PROVIDER=postmark` + `LOCK_POSTMARK_TOKEN`, and `LOCK_PUSH_PROVIDER=expo` (2.6, 2.7).
  The `prod` profile refuses to start with the log-only email sender.
- Sealed vaults (roadmap 3.6) need no extra infrastructure: keys, media and search tokens are handled
  on the devices. The API's storage credentials also need `s3:GetLifecycleConfiguration` for the F6 check.
