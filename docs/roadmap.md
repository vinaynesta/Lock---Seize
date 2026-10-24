# Lock roadmap: features, effort and priority

This is the working plan for everything after the MVP. Work through it top to bottom.
Before starting a feature, re-read its entry. After finishing one, tick it off and update
[`decisions.md`](decisions.md) if it settled an open question.

- Design reference: [`Lock_High_Level_Detailed_System_Design.md`](Lock_High_Level_Detailed_System_Design.md)
- Decisions already taken: [`decisions.md`](decisions.md)

_Last reviewed: 2026-10-24_

---

## How to read this

**Priority**

| Level | Meaning |
|---|---|
| **P0** | Blocks other work or leaves the repo broken. Do it now. |
| **P1** | Required before any real user data goes in (closed beta). |
| **P2** | Required before a public launch. |
| **P3** | Growth and premium. Build when there is demand. |

**Effort.** One developer who knows the codebase. Includes tests and docs.

| Size | Ideal days |
|---|---|
| **XS** | < 0.5 |
| **S** | 1–2 |
| **M** | 3–5 |
| **L** | 6–10 |
| **XL** | 10+ (split it before starting) |

**Definition of done (every feature)**
- Backend: integration test against embedded Postgres (see `support/IntegrationTest.java`). Schema changes go in a new Flyway migration `V<n>__*.sql`. Never edit `V1`.
- Client: `npx tsc --noEmit && npx expo lint` pass. The screen works on web and on one native target.
- Domain invariants still hold (locked content immutable, non-members get 404, audit trail append-only).
- `README.md` API table and config table updated if endpoints or env vars changed.
- New domain events go through the outbox (`OutboxPublisher`), never directly to a side effect.
- **Security check after tests pass:**
  - `.gitignore` covers any new build output, env files, keys or logs, and `git status` shows nothing that should be ignored.
  - No hard-coded secrets in the diff. Only dev defaults are allowed, using the `${ENV:default}` pattern.
  - No tokens, passwords or decrypted content in logs.
  - New endpoints have the right auth rule in `SecurityConfig`.

---

## Summary

| # | Feature | Priority | Effort | Depends on | Status |
|---|---|---|---|---|---|
| 0.1 | Repo hygiene: untrack `target/`, restore `application.yml` | P0 | XS | — | ✅ |
| 0.2 | CI pipeline (backend tests + client checks) | P0 | S | 0.1 | ✅ |
| 1.1 | Refresh tokens with rotation | P1 | M | 0.2 | ✅ |
| 1.2 | Rate limiting and login lockout | P1 | S | — | ✅ |
| 1.3 | Scheduler locking (ShedLock) | P1 | S | — | ✅ |
| 1.4 | Production key management (KMS for the KEK) | P1 | M | — | ✅ |
| 1.5 | Observability baseline | P1 | S | — | ✅ |
| 1.6 | Timeline pagination (cursor) and an export without the 200 cap | P1 | S | — | ✅ |
| 2.1 | Media processing worker: thumbnails | P2 | L | 1.3 | ✅ |
| 2.2 | Malware scanning of uploads | P2 | M | 2.1 | ✅ |
| 2.3 | Full ZIP export with original media | P2 | M | 1.6, 2.1 worker seam | ✅ |
| 2.4 | Vault-key rotation on member removal | P2 | L | 1.4 | ✅ |
| 2.5 | Account deletion | P2 | M | Policy C chosen | ✅ |
| 2.6 | Password reset by email | P2 | M | 1.2 | ✅ |
| 2.7 | Push notifications (on this day, capsule reveal, invites) | P2 | M | outbox | ✅ |

| 2.8 | Video/audio transcoding for playback | P2 | L | 2.1 | ✅ |
| 3.1 | MFA (TOTP) | P3 | M | 1.1 | ✅ |
| 3.2 | Storage quotas per plan | P3 | M | — | ✅ |
| 3.3 | Cold-tier archival of old media | P3 | M | 3.2 | ✅ |items 
| 3.4 | Search over memories | P3 | L | — | ✅ |
| 3.5 | Offline drafts and background upload (client) | P3 | L | — | ✅ |
| 3.6 | Sealed Vault (end-to-end encryption tier) | P3 | XL | 2.4, recovery decision | ⚠ design written, blocked on recovery decision |

**Rough totals.** P0 ≈ 2 days · P1 ≈ 3–4 weeks · P2 ≈ 8–10 weeks · P3 is open-ended.

---

## P0: fix now

### 0.1 Repo hygiene · XS
**Why.** A fresh clone does not start, and build output pollutes every diff.
- `backend/src/main/resources/application.yml` is listed in `.gitignore` (line 7) and is not tracked. The only copy in git is the stale `backend/target/classes/application.yml`. Spring cannot find its config.
- `backend/target/` (about 120 files: `.class` files, surefire reports) is committed, even though `.gitignore` already lists `target/`.

**Scope**
- `git rm -r --cached backend/target`.
- Remove `backend/src/main/resources/application.yml` from `.gitignore` and commit the file. It holds only `${ENV:dev-default}` placeholders. Real secrets come from env vars.
- Optional: rotate the dev JWT secret and master key defaults, since they are already in history (dev-only, low risk).

**Done when.** A fresh clone followed by the README "no Docker" command starts the API.

> ✅ Done 2026-10-17. Also fixed: `backend/mvnw` was committed without the executable bit.

### 0.2 CI pipeline · S
**Why.** `.github/workflows/` is empty. Nothing checks PRs.

**Scope**
- GitHub Actions: `backend` job (`temurin 21`, `./mvnw -B verify`, Maven cache) and `client` job (Node 20, `npm ci`, `tsc --noEmit`, `expo lint`).
- Run on PRs to `main` and on pushes to `main`.
- Branch protection on `main` that requires both jobs.

**Done when.** A PR shows two green checks and cannot merge while they are red.

> ✅ Workflow added 2026-10-17 (`.github/workflows/ci.yml`). Still to do by hand: enable branch protection on `main` in GitHub settings.

---

## P1: before beta (real user data)

### 1.1 Refresh tokens with rotation · M
**Why.** There is only one access token, with a 1-hour TTL (`lock.jwt.ttl: PT1H`). Mobile users get logged out every hour, and the only fix today is a long TTL, which is a security risk.

**Scope**
- New table `refresh_tokens (id, user_id, family_id, token_hash, expires_at, revoked_at, replaced_by, created_at, user_agent)`. Store only a SHA-256 of the token.
- Access TTL 15 min. Refresh TTL 30 days, sliding.
- `POST /auth/refresh`: rotates on every use. **Reuse detection:** presenting an already-rotated token revokes the whole family.
- `POST /auth/logout` revokes the current family. Password change (`AuthController` change-password) revokes all of the user's families.
- Audit events: `TOKEN_REFRESHED`, `TOKEN_REUSE_DETECTED`, `LOGOUT`.
- Client: keep the refresh token in `tokenStore.ts` (SecureStore on native). In `api/client.ts`, a 401 triggers one refresh (single-flight, so parallel requests share it), then replays the request. If the refresh fails, sign out.

**Files.** `identity/TokenService.java`, `identity/AuthController.java`, `identity/IdentityService.java`, new `RefreshToken*`, `V2__refresh_tokens.sql`, `client/src/api/client.ts`, `client/src/lib/tokenStore.ts`, `client/src/lib/session.tsx`.

**Tests.** Rotation, reuse revokes the family, expiry, password change revokes everything, concurrent refresh on the client.

> ✅ Done 2026-10-17. As built: `RefreshTokenService` (rotation, reuse detection, family revoke), `V2__refresh_tokens.sql`, `POST /auth/refresh` and `/auth/logout` (they ignore the `Authorization` header). Change-password now returns a new token pair for the calling device. A cleanup job deletes expired tokens every 6h. The client refreshes once, shared across parallel requests, on a 401 without an error code. Not done: storing a `user_agent` per token, and automated client tests (the client has no test runner yet).

### 1.2 Rate limiting and login lockout · S
**Why.** `/auth/login` and `/auth/register` have no limits, so password guessing is free. Deletion-request re-auth needs the same protection.

**Scope**
- Bucket4j (in-memory now, Redis-backed later) as a servlet filter. Per-IP and per-email limits on `/auth/*`, and a per-user limit on the deletion-request re-auth.
- Progressive delay or a temporary lockout after N failed logins per account. Audit `LOGIN_FAILED` and `ACCOUNT_LOCKED`.
- Return `429` with a `Retry-After` header. Limits are configurable in `LockProperties`.

**Done when.** A test shows the 6th failed login inside a minute returns 429.

> ✅ Done 2026-10-17, without Bucket4j: Maven Central could not be reached from the dev machine (TLS/PKIX), so it uses a small in-memory sliding window (`security/RateLimiter`). As built:
> - `AuthRateLimitFilter` allows 20 requests per minute per IP per `/auth` endpoint.
> - `LoginAttemptGuard` locks an account for 15 minutes after 5 wrong passwords, from any IP. It counts unknown emails too, so the lockout doesn't reveal which accounts exist. Change-password and deletion re-auth share their own per-user counter.
> - Over the limit returns 429 with `Retry-After`. Limits are configurable under `lock.rate-limit`.
>
> Known limits:
> - Counts are per instance. Move them to Redis before running several instances.
> - Anyone can lock out a known email for 15 minutes (the standard trade-off). Revisit with CAPTCHA or MFA.
> - Behind a load balancer, set `LOCK_FORWARD_HEADERS=framework`, or every client will share the balancer's IP.

### 1.3 Scheduler locking (ShedLock) · S
**Why.** `LifecycleJobs` (seal, purge, expire uploads) and `OutboxDispatcher` run `@Scheduled` on every instance. With two instances, jobs run twice: double purges, double event dispatch.

**Scope**
- ShedLock with the JDBC provider and a `shedlock` table migration. `@SchedulerLock` on each job.
- The outbox dispatcher can use `SELECT … FOR UPDATE SKIP LOCKED` instead, so it can scale out. Pick one approach and document it in `decisions.md`.

**Done when.** A test with two dispatcher beans processes each event exactly once.

> ✅ Done 2026-10-17 with `FOR UPDATE SKIP LOCKED` instead of ShedLock, so there is no new dependency and instances share the work instead of one sitting idle. The outbox already used it. Seal, purge and upload-expiry now claim their batches the same way. `ConcurrentJobsIntegrationTest` runs 4 concurrent seal jobs over 40 memories and checks exactly 40 locks and 40 audit events. It fails if `SKIP LOCKED` is removed. The refresh-token cleanup is a single idempotent `DELETE`, so it needs no claim.

### 1.4 Production key management · M
**Why.** The KEK is a config value (`LOCK_MASTER_KEY`). `decisions.md` says to replace it before production.

**Scope**
- `KmsKeyEncryptionService` (AWS KMS first) behind the existing `KeyEncryptionService` interface. Choose it with `lock.crypto.provider=local|aws-kms`.
- Add a `kek_id` / `kek_version` column on the wrapped vault key so KEKs can be rotated. A re-wrap job moves vault keys to a new KEK without touching content.
- Startup check: refuse to boot in the `prod` profile with the dev default key or JWT secret.

**Done when.** Integration test with a fake KMS. The prod profile refuses to start with dev defaults.

> ✅ Done 2026-10-17.
> - **Prod guard:** `ProductionSecretsGuard` (profile `prod`) refuses to boot while `LOCK_JWT_SECRET`, `LOCK_S3_SECRET_KEY` or, with a local KEK, `LOCK_MASTER_KEY` still has its dev default.
> - **KMS:** `AwsKmsKeyEncryptionService` is selected with `LOCK_CRYPTO_PROVIDER=aws-kms` and `LOCK_KMS_KEY_ID`. Credentials come from the default AWS chain (an IAM role in production). It uses a fixed encryption context, and `Decrypt` is pinned to the key id.
> - **Rotation:** `V3__vault_kek_id.sql` records which KEK wrapped each vault key. To rotate: set the new key, put the old one in `LOCK_RETIRED_MASTER_KEYS`, and deploy. `VaultKeyRewrapper` then re-wraps vault keys in batches every 10 minutes, using `SKIP LOCKED`. When the `lock.vaults.kek.stale` metric reaches 0, remove the old key. The same path moves an existing local deployment onto KMS. Memory content is never re-encrypted, so locked memories and integrity proofs are untouched (tested).
> - **Not tested against real AWS:** the tests use an in-memory fake KMS.
> - **Still to do:** the IAM policy (`kms:Encrypt` and `kms:Decrypt` on that one key only) and enabling KMS automatic key rotation. Both are infrastructure, not code.

### 1.5 Observability baseline · S
**Why.** The design doc (§38) lists metrics. There are only bare actuator endpoints today.

**Scope**
- Micrometer + Prometheus endpoint. Custom metrics: memories locked, seal-job lag, outbox backlog and age of the oldest event, upload failures, integrity-check failures.
- Structured JSON logs with a request id. Never log decrypted content or tokens.
- Readiness and liveness probes that include DB and storage health.

> ✅ Done 2026-10-17.
> - **Prometheus registry,** with metrics: `lock.memories.locked`, `lock.memories.purged`, `lock.integrity.failures`, `lock.uploads.failed`, `lock.seal.lag` (seconds), `lock.outbox.pending`, `lock.outbox.dead`, `lock.outbox.oldest.age` and `lock.vaults.kek.stale`. Tags never include user, vault or memory ids.
> - **Request ids:** `RequestIdFilter` adds an `X-Request-Id` to every response and log line. Incoming ids are kept only if they are log-safe.
> - **Logs:** set `LOCK_LOG_FORMAT=ecs` for JSON logs.
> - **Health:** `/actuator/health/readiness` checks DB and storage, `/liveness` is separate, and details are never shown publicly.
> - **Metrics access:** `/actuator/metrics` and `/actuator/prometheus` are **refused on the app port** (403/401). Set `LOCK_MANAGEMENT_PORT` to serve them on a private port for scraping.
> - **Not done:** alert rules and dashboards (infrastructure).

### 1.6 Timeline pagination and an uncapped export · S
**Why.** The timeline uses `limit` only (default 50). `ExportController` silently stops at `EXPORT_LIMIT = 200`, so users with more memories get an **incomplete export without any warning**. That breaks the "export anytime" promise.

**Scope**
- Keyset cursor (`capturedAt, id`) on `GET /vaults/{id}/memories`. The response returns `nextCursor`.
- Export streams the vault in pages (no cap). The JSON is written with a streaming writer.
- Client timeline uses infinite scroll (`useInfiniteQuery`).

> ✅ Done 2026-10-17. `GET /vaults/{id}/memories` takes `?cursor=` and returns `Next-Cursor` when more exist. The body is still a plain array, so older clients keep working. The cursor is an opaque base64 `(occurredAt, id)`. The export uses the same paging. The client shows a "Load older memories" button (`useInfiniteQuery`), with 50 per page.

---

## P2: before public launch

### 2.1 Media processing worker: thumbnails · L
**Why.** `AssetGallery.tsx` loads full-size originals (up to 50 MB photos). That is slow and costly on mobile, and Relive is the core experience.

**Scope**
- On `ASSET_COMPLETED` (outbox), a worker generates WebP derivatives (256 px, 1024 px), strips EXIF GPS from the derivatives only, and stores them under `derived/`. Originals are never modified, so integrity hashes stay valid.
- `asset_derivatives` table. The API returns a thumbnail URL when one exists and falls back to the original.
- Derivatives are *not* part of the integrity proof and *not* object-locked (they can be regenerated).
- Keep the worker in-process for now, behind an interface, so it can move to a separate service or queue later.

> ✅ Done 2026-10-23. `media/AssetProcessor` handles `asset.scanned_clean` from the outbox and writes JPEG derivatives (THUMB 256 px, PREVIEW 1024 px) under `derived/<original key>/`, recorded in `asset_derivatives` (`V4`). As built:
> - JPEG, not WebP: the JDK has no WebP encoder and adding a native one wasn't worth it yet. Re-encoding drops all EXIF (so GPS too), and the EXIF orientation is applied first so phone photos are upright.
> - `GET /assets/{id}/download?variant=thumb|preview` returns the derivative, or the original if there is none (the response's `variant` says which). Asset views list the available `variants`.
> - Derivatives are not in the integrity proof, not object-locked, can be written after locking, and are deleted with their original.
> - A 30-minute backfill job covers photos uploaded before 2.1. The client grid uses THUMB; Relive uses PREVIEW; tapping opens the original.
> - **Not done:** HEIC/HEIF and WebP originals get no thumbnail (ImageIO can't read them), so they fall back to the original. Most iPhone photos arrive as JPEG through the picker, but this needs a decoder before launch if HEIC uploads are common.

### 2.2 Malware scanning · M
- ClamAV sidecar (or a cloud scanning API). Uploads stay `PENDING_SCAN` until clean. Infected files are quarantined, and the asset is marked `REJECTED` and reported to the owner.
- This decides whether memories with unscanned assets can be locked. Recommendation: block locking until every asset is scanned.

> ✅ Done 2026-10-23. Decision: **block locking until every asset is scanned clean** (decisions §16). As built:
> - `complete` now moves a verified upload to `PENDING_SCAN` and publishes `asset.verified`. `AssetProcessor` streams it to the scanner outside any transaction, then commits the verdict: `VERIFIED` (usable) or `REJECTED` with the bytes moved to `quarantine/` and an `ASSET_REJECTED` audit event naming the signature.
> - `ClamAvScanner` speaks clamd's INSTREAM protocol (`LOCK_SCAN_PROVIDER=clamav`). `DevMalwareScanner` only detects the EICAR test file and is the default; the `prod` profile refuses to start with it.
> - A scanner outage leaves assets pending, never "clean": the outbox retries, and a 5-minute sweep picks up anything it gave up on. Metrics: `lock.scan.pending`, `lock.uploads.rejected`.
> - Existing `VERIFIED` draft assets are sent back through the scan by `V4`.
> - **Still to do (infrastructure):** run clamd with `StreamMaxLength` ≥ 2 GB (the compose file has a `scan` profile), and alert on `lock.scan.pending` growing. A user notification for rejected files is 2.7; today the memory screen shows it.

### 2.3 Full ZIP export with original media · M
**Why.** Today's export is JSON only. It is the follow-up named in decisions §13 and a key trust feature for a permanence app.
- `POST /vaults/{id}/exports` starts an async job (outbox). The worker builds the ZIP (manifest JSON + original media + integrity hashes) in storage. The user gets a short-lived download URL and a notification (2.7).
- Export jobs are audited. Only one active export per user per vault. The ZIP expires after 7 days.

> ✅ Done 2026-10-23. `export/ExportService` (`V5__vault_exports.sql`). As built:
> - `POST /vaults/{id}/exports` queues a job (one `QUEUED` per user per vault, enforced by a partial unique index); the outbox worker writes `manifest.json` (`lock-export/v2`: memories, reflections, content hashes, per-file SHA-256 and path) and `media/<memoryId>/<assetId>.<ext>` into a temp file, streaming page by page, then stores it under `exports/`.
> - Exports contain exactly what the requester can see (their own drafts, no unopened capsules or rejected files). Only the requester can see or download one, and only while still a member. The temp file is always deleted.
> - Audited: `EXPORT_REQUESTED`, `EXPORT_COMPLETED`, `EXPORT_FAILED`, `EXPORT_DOWNLOADED`. An hourly job deletes archives after 7 days and fails exports stuck for 6 hours.
> - Client: "Export this vault" on the timeline opens a screen that polls and offers the download.
> - **Not done:** the "ready" notification (2.7; `export.ready` is already published). Archives are not encrypted beyond storage SSE, so the bucket's `exports/` prefix should get a lifecycle rule as a backstop.

### 2.4 Vault-key rotation on member removal · L
**Why.** A removed member who kept the old vault key could decrypt content they obtain later (design §22). The gap is known and listed as "still open".
- Vault keys get versions (`vault_keys(vault_id, version, wrapped_key)`). Ciphertext records its key version.
- Removing a member (or a member leaving) creates a new key version. New content uses the new version.
- Old locked content stays under the old version, because re-encrypting would conflict with the immutability triggers and the integrity hashes. Document this trade-off in `decisions.md` before building.

> ✅ Done 2026-10-23. Trade-off recorded in decisions §15. As built:
> - `V6__vault_key_versions.sql` moves wrapped keys into `vault_keys(vault_id, version, wrapped_key, kek_id)` and adds `memory_reflections.key_version`. `vaults.key_version` is the current version.
> - Removing an **active** member, a member leaving (unless they were the last), or a member's account being deleted adds a version (`VAULT_KEY_ROTATED` audit event). Revoking an invitation does not: the invitee never had the key.
> - New memories and reflections use the current version; each is decrypted with its own. A draft is re-encrypted under the current key when next edited. Locked ciphertext is never touched (tested byte for byte, and the integrity proof still holds).
> - KEK rotation (1.4) now re-wraps every key version (`lock.vaults.kek.stale` counts versions).

### 2.5 Account deletion · M · ⚠ blocked
**Blocked on** a product/legal decision between policies A, B and C (design doc §41). Also needed for GDPR/CCPA.
- Recommended starting point is **Policy C**: personal vaults are purged through the existing deletion-request path (30-day grace). In shared vaults, the user's contributions stay, attributed to a "former member".
- Record the decision in `decisions.md` first. Then reuse the `DeletionRequest` + purge job machinery.

> ✅ Done 2026-10-23 with **Policy C** (decisions §14). As built:
> - `POST /account/deletion-request` (password required, shares the re-auth lockout), `GET` to see it, `DELETE` to cancel. It runs after 30 days (`lock.account.deletion-grace-period`) in `account/AccountService`, one account per transaction, claimed with `SKIP LOCKED`.
> - On execution: personal vaults and all their memories are purged through the same trigger-bypass purge path as single deletions; the user leaves every shared vault (with key rotation, or DORMANT if last); their shared-vault drafts are purged; their exports are deleted; the user row is anonymised (`DELETED`, "Former member", random unusable password) and all sessions revoked. Media is removed by the outbox. `ACCOUNT_DELETED` stays on the audit trail.
> - Member lists now return `displayName`, so remaining members see "Former member".
> - Client: Settings → "Delete account" explains what happens and allows cancelling.
> - **Still open:** legal sign-off on Policy C (decisions "Still open"); reminder notifications at 7 and 1 days (2.7; `account.deletion.requested` is already published).

### 2.6 Password reset by email · M
- Email provider seam (SES/Postmark), single-use reset token (hashed, 30 min), revokes all refresh tokens.
- Content is unaffected because vault keys are wrapped by the KEK, not the password (decisions §2). This only works under server-side encryption. Sealed Vault (3.6) needs a different recovery model.

> ✅ Done 2026-10-24 (decisions §18). `POST /auth/password-reset` always answers 202 (no account
> enumeration; 3 requests per email per hour); `POST /auth/password-reset/confirm` sets the password,
> revokes every session and clears the login lockout. Tokens are single-use, 30 minutes, newest-only,
> stored as SHA-256; the raw token is an HMAC of the row id, so it never sits in the outbox. A signed-in
> password change also kills outstanding links. Email goes through an `EmailSender` seam: `log` (dev)
> or `postmark`; the `prod` profile refuses `log`. Client: "Forgot your password?" on sign-in and a
> `/reset-password?token=` screen.

### 2.7 Push notifications · M
- Expo push tokens per device (`devices` table, design §23).
- Events through the outbox: memory sealed, time capsule revealed, "on this day", vault invitation, deletion request about to execute (reminders at 7 days and 1 day). That last one is a safety feature.
- Also consume the events 2.2, 2.3 and 2.5 already publish: `asset.rejected` (a file failed the malware scan), `export.ready` (ZIP is downloadable) and `account.deletion.requested` (reminders at 7 days and 1 day before the account is deleted).
- Users can choose which notifications they get.

> ✅ Done 2026-10-24 (decisions §19). `devices` (Expo push tokens, moved to the newest account on
> re-registration), `notification_preferences` (opt-outs; deletion reminders cannot be turned off) and
> a `notifications` queue with dedupe keys and scheduled `send_at`. `NotificationEventHandler` turns
> outbox events into notifications: memory locked, capsule opened (scheduled for `revealAt`, sent to
> whoever is a member then), vault invitation, asset rejected, export ready, and deletion reminders at
> 7 and 1 days for memories and the account (cancelled with the request). Daily "On this day" job at
> 09:00 UTC. Texts are generic: no memory content ever goes to a push provider. Sender seam: `log` or
> `expo`; dead tokens are dropped. Client: registers on sign-in (native), unregisters on sign-out,
> opens the right screen on tap, Settings → Notifications.

### 2.8 Video/audio transcoding · L
- HLS/MP4 renditions for playback, generated by the 2.1 worker. Same rules as thumbnails: originals untouched, renditions can be regenerated.

> ✅ Done 2026-10-24 (decisions §17, updated). A `PLAYBACK` derivative: H.264/AAC MP4, at most
> 1280 px, never upscaled, `+faststart`, metadata stripped. Audio gets AAC in MP4. Videos also get
> THUMB/PREVIEW from a still frame. Runs `ffmpeg` behind a `MediaTranscoder` seam
> (`LOCK_TRANSCODE_PROVIDER=ffmpeg`; the Docker image ships it), on temp files so a 2 GB video never
> sits in memory, with a timeout and `-protocol_whitelist file`. A file ffmpeg can't read falls back
> to the original; the memory can still lock. Progressive MP4 rather than HLS: one file plays on every
> phone and browser, and adaptive bitrate is not worth the complexity at these sizes. Tested against
> the real ffmpeg (`FfmpegTranscoderTest`, CI installs it). Same worker also does F7.

---

## P3: growth and premium

| # | Feature | Notes |
|---|---|---|
| 3.1 | **MFA (TOTP)** · M | Required for deletion requests and export once enabled. Recovery codes. |
| 3.2 | **Storage quotas** · M | Per-plan bytes per user. Enforced at `upload` (presign) time. Usage meter in settings. |
| 3.3 | **Cold-tier archival** · M | Move originals of memories older than N months to an infrequent-access tier, and keep derivatives hot. Watch out for retrieval latency in Relive. |
| 3.4 | **Search** · L | Content is encrypted, so Postgres full-text search on plaintext is not available. Options: a search index over client-provided tags/titles only, or an encrypted blind index. Needs a design note first. |
| 3.5 | **Offline drafts + background upload** · L | Local draft storage and a resumable upload queue in the client. |
| 3.6 | **Sealed Vault (E2E)** · XL | Premium tier. Needs a recovery-model decision (design §21) and 2.4 versioned keys. Server-side features (thumbnails, integrity re-check of plaintext, search) degrade. Write a design doc before estimating. |

**P3 status (2026-10-24)**

- ✅ **3.1 MFA.** TOTP (RFC 6238; secret wrapped by the KEK; each 30-second step accepted once) and
  10 single-use recovery codes. With MFA on, login returns an `mfaToken` (an HMAC ticket valid 5
  minutes, deliberately not a JWT) to send with a code to `POST /auth/login/mfa`. Deletion requests
  (memory and account) and both exports need the code in an `X-MFA-Code` header. Wrong codes share
  the lockout. Endpoints under `/account/mfa`. Client: two-step sign-in, Settings → Two-step
  verification, code field on the sensitive screens.
- ✅ **3.2 Storage quotas.** Plans in `lock.quota.plans` (free 5 GB, plus 200 GB, premium 2 TB),
  `users.plan` (null = default). Usage is the bytes of original media in memories the user created,
  including in-flight uploads, so parallel presigns can't overshoot. Enforced at presign (`413
  QUOTA_EXCEEDED`). Nothing preserved is ever touched when over quota. `GET /account/storage` and a
  meter in Settings. No self-service plan change (billing is out of scope).
- ✅ **3.3 Cold tier.** A job tags originals of memories locked over a year ago (`lock-tier=cold`); a
  bucket lifecycle rule (`deploy/s3-lifecycle.json`) moves them to Glacier Instant Retrieval, so Relive
  never waits on a restore. Tagging keeps the object version, retention and checksum. Derivatives
  stay hot. Sealed capsules wait until they have opened. V13 lets the asset trigger accept changes to
  `archived_at` only. Off by default (`LOCK_COLD_TIER_ENABLED`).
- ✅ **3.4 Search.** A per-vault blind index (decisions §21): HMAC tokens of normalised words and
  prefixes, under a key derived from the vault key version. `GET /vaults/{id}/search?q=`. AND across
  words, case and accent-insensitive, prefix of 3+ letters. Same visibility as the timeline; sealed
  capsules don't match until they open. Backfill job for older memories. Client: search box on the
  vault screen.
- ✅ **3.5 Offline drafts + background upload.** Client queue persisted on the device
  (`lib/offlineQueue.ts`): drafts created offline (fixed Idempotency-Key, so a retry can't
  duplicate), and every native upload copied into app storage and sent with the native uploader
  (streamed from disk). Drains on launch and when connectivity returns; permanent failures stay
  visible with Retry/Remove. Native only; web uploads go straight through.
- ⚠ **3.6 Sealed Vault.** Design note written: [`sealed-vault-design.md`](sealed-vault-design.md).
  Recommends a recovery phrase plus device-to-device approval; lists what degrades and an XL
  estimate split into pieces. **Blocked** on the recovery-model decision (open decisions below).

---

## Follow-ups from finished work

Loose ends left by features marked ✅ above. Tick them off here; they are not tracked anywhere else.

| # | Task | From | Type | Before | Status |
|---|---|---|---|---|---|
| F1 | Enable branch protection on `main` requiring the `backend` and `client` CI checks | 0.2 | Manual (GitHub settings) | Now | ☐ needs a repo admin |
| F2 | Test `ClamAvScanner` against a real clamd: clean file, EICAR file, clamd stopped (asset must stay `PENDING_SCAN`) | 2.2 | Verification | Beta | ✅ `RealClamdIntegrationTest` (real clamd 1.4, runs when one answers on `LOCK_IT_CLAMD_PORT`) + `ClamdDownIntegrationTest` (always runs) |
| F3 | Raise clamd `StreamMaxLength` to ≥ 2 GB in production. Alert on `lock.scan.pending` growing | 2.2 | Infrastructure | Beta | ◐ `deploy/clamd.conf` (2100M, mounted by compose). Still to do: use it in production and add the alert |
| F4 | Set `LOCK_SCAN_PROVIDER=clamav` in every `prod` deployment; the prod profile refuses to start without it | 2.2 | Infrastructure | Beta | ◐ enforced by `ProductionSecretsGuard`; set it in each deployment (checklist in `deploy/README.md`) |
| F5 | Test the `S3ObjectStorage` calls (`read`, `write`, `quarantine`, deleting a missing derivative key, object lock) against MinIO | 2.1–2.3 | Verification | Beta | ✅ `S3ObjectStorageMinioTest` (runs when MinIO answers on `LOCK_IT_S3_ENDPOINT`), incl. signed-checksum rejection, governance retention and cold-tier tagging |
| F6 | Bucket lifecycle rule expiring `exports/` after ~8 days | 2.3 | Infrastructure | Beta | ◐ `deploy/s3-lifecycle.json`; applied to local MinIO by compose. Apply to the production bucket |
| F7 | Thumbnails for HEIC/HEIF and WebP originals | 2.1 | Feature · S–M | Launch | ✅ decoded by the 2.8 transcoder (ffmpeg) |
| F8 | Legal sign-off that Policy C satisfies erasure requests. If counsel wants Policy A, also purge the user's locked memories in shared vaults (decisions §14) | 2.5 | Decision (legal) | Launch | ☐ needs counsel |
| F9 | Store `user_agent` per refresh token, and add a client test runner for the token-refresh logic | 1.1 | Feature · S | Launch | ✅ V15 + `GET /auth/sessions`; Jest (`jest-expo`) with `src/api/__tests__/client.test.ts`, run in CI |

Notifications for rejected files, ready exports and account-deletion reminders are part of 2.7, not listed here.

---

## Open decisions (resolve before the blocked work)

| Decision | Blocks | Owner |
|---|---|---|
| ~~Account deletion policy (A/B/C, design §41)~~ Policy C, pending legal sign-off | 2.5 | Product + legal |
| ~~Can memories lock before malware scan completes?~~ No | 2.2 | Product |
| ~~Old content stays on old key version after member removal?~~ Yes (decisions §15) | 2.4 | Engineering |
| Recovery model for Sealed Vault (options and recommendation in `sealed-vault-design.md` §2) | 3.6 | Product + security |
| Sealed Vault: malware policy, web support, pricing (`sealed-vault-design.md` §6) | 3.6 | Product |
| ~~ShedLock vs `SKIP LOCKED` for the outbox~~ `SKIP LOCKED` | 1.3 | Engineering |

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-17 | First version, based on `decisions.md` "Still open" and a review of the code. |
| 2026-10-17 | Done: 0.1, 0.2 (branch protection still manual), 1.1, and the export half of 1.6. |
| 2026-10-17 | Done: 1.2, 1.3, 1.6, and the 1.4 prod startup guard. Next: 1.5 observability, then 1.4 KMS. |
| 2026-10-17 | Done: 1.4 (KMS + KEK rotation) and 1.5. **All of P1 is done.** Next: P2, starting with 2.1 thumbnails. |
| 2026-10-23 | Restored 0.1 and 0.2 on `main` (the PR #2 squash merge had dropped them: `backend/target/` was tracked again, `ci.yml` was missing, `mvnw` lost its executable bit). Also restored the dev-default constants in `ProductionSecretsGuard`, which PR #3 had blanked, so the prod guard works again. |
| 2026-10-23 | Done: 2.1, 2.2, 2.3, 2.4, 2.5 (Policy C). Next: 2.6 password reset. |
| 2026-10-23 | Added "Follow-ups from finished work" (F1–F9) so loose ends from finished features are tracked; widened 2.7 to cover the events 2.2/2.3/2.5 publish. |
| 2026-10-24 | Done: 2.6, 2.7, 2.8 (**all of P2**), 3.1–3.5, F2, F5, F7, F9. Partly: F3, F4, F6 (config written, needs applying in production). Open: 3.6 (design written, waits on the recovery decision), F1 (GitHub admin), F8 (legal). |
