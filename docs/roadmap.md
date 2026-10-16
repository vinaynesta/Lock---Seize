# Lock roadmap: features, effort and priority

This is the working plan for everything after the MVP. Work through it top to bottom.
Before starting a feature, re-read its entry. After finishing one, tick it off and update
[`decisions.md`](decisions.md) if it settled an open question.

- Design reference: [`Lock_High_Level_Detailed_System_Design.md`](Lock_High_Level_Detailed_System_Design.md)
- Decisions already taken: [`decisions.md`](decisions.md)

_Last reviewed: 2026-10-17_

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
| 1.2 | Rate limiting and login lockout | P1 | S | — | ☐ |
| 1.3 | Scheduler locking (ShedLock) | P1 | S | — | ☐ |
| 1.4 | Production key management (KMS for the KEK) | P1 | M | — | ☐ |
| 1.5 | Observability baseline | P1 | S | — | ☐ |
| 1.6 | Timeline pagination (cursor) and an export without the 200 cap | P1 | S | — | ◐ export done |
| 2.1 | Media processing worker: thumbnails | P2 | L | 1.3 | ☐ |
| 2.2 | Malware scanning of uploads | P2 | M | 2.1 | ☐ |
| 2.3 | Full ZIP export with original media | P2 | M | 1.6, 2.1 worker seam | ☐ |
| 2.4 | Vault-key rotation on member removal | P2 | L | 1.4 | ☐ |
| 2.5 | Account deletion | P2 | M | **legal decision** | ☐ |
| 2.6 | Password reset by email | P2 | M | 1.2 | ☐ |
| 2.7 | Push notifications (on this day, capsule reveal, invites) | P2 | M | outbox | ☐ |
| 2.8 | Video/audio transcoding for playback | P2 | L | 2.1 | ☐ |
| 3.1 | MFA (TOTP) | P3 | M | 1.1 | ☐ |
| 3.2 | Storage quotas per plan | P3 | M | — | ☐ |
| 3.3 | Cold-tier archival of old media | P3 | M | 3.2 | ☐ |
| 3.4 | Search over memories | P3 | L | — | ☐ |
| 3.5 | Offline drafts and background upload (client) | P3 | L | — | ☐ |
| 3.6 | Sealed Vault (end-to-end encryption tier) | P3 | XL | 2.4, recovery decision | ☐ |

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

### 1.3 Scheduler locking (ShedLock) · S
**Why.** `LifecycleJobs` (seal, purge, expire uploads) and `OutboxDispatcher` run `@Scheduled` on every instance. With two instances, jobs run twice: double purges, double event dispatch.

**Scope**
- ShedLock with the JDBC provider and a `shedlock` table migration. `@SchedulerLock` on each job.
- The outbox dispatcher can use `SELECT … FOR UPDATE SKIP LOCKED` instead, so it can scale out. Pick one approach and document it in `decisions.md`.

**Done when.** A test with two dispatcher beans processes each event exactly once.

### 1.4 Production key management · M
**Why.** The KEK is a config value (`LOCK_MASTER_KEY`). `decisions.md` says to replace it before production.

**Scope**
- `KmsKeyEncryptionService` (AWS KMS first) behind the existing `KeyEncryptionService` interface. Choose it with `lock.crypto.provider=local|aws-kms`.
- Add a `kek_id` / `kek_version` column on the wrapped vault key so KEKs can be rotated. A re-wrap job moves vault keys to a new KEK without touching content.
- Startup check: refuse to boot in the `prod` profile with the dev default key or JWT secret.

**Done when.** Integration test with a fake KMS. The prod profile refuses to start with dev defaults.

### 1.5 Observability baseline · S
**Why.** The design doc (§38) lists metrics. There are only bare actuator endpoints today.

**Scope**
- Micrometer + Prometheus endpoint. Custom metrics: memories locked, seal-job lag, outbox backlog and age of the oldest event, upload failures, integrity-check failures.
- Structured JSON logs with a request id. Never log decrypted content or tokens.
- Readiness and liveness probes that include DB and storage health.

### 1.6 Timeline pagination and an uncapped export · S
**Why.** The timeline uses `limit` only (default 50). `ExportController` silently stops at `EXPORT_LIMIT = 200`, so users with more memories get an **incomplete export without any warning**. That breaks the "export anytime" promise.

**Scope**
- Keyset cursor (`capturedAt, id`) on `GET /vaults/{id}/memories`. The response returns `nextCursor`.
- Export streams the vault in pages (no cap). The JSON is written with a streaming writer.
- Client timeline uses infinite scroll (`useInfiniteQuery`).

> ◐ Export part done 2026-10-17: `ExportController` walks the vault with an `(occurredAt, id)` keyset cursor (`MemoryService.timelineAfter`). Still to do: a cursor on the public timeline endpoint and infinite scroll in the client.

---

## P2: before public launch

### 2.1 Media processing worker: thumbnails · L
**Why.** `AssetGallery.tsx` loads full-size originals (up to 50 MB photos). That is slow and costly on mobile, and Relive is the core experience.

**Scope**
- On `ASSET_COMPLETED` (outbox), a worker generates WebP derivatives (256 px, 1024 px), strips EXIF GPS from the derivatives only, and stores them under `derived/`. Originals are never modified, so integrity hashes stay valid.
- `asset_derivatives` table. The API returns a thumbnail URL when one exists and falls back to the original.
- Derivatives are *not* part of the integrity proof and *not* object-locked (they can be regenerated).
- Keep the worker in-process for now, behind an interface, so it can move to a separate service or queue later.

### 2.2 Malware scanning · M
- ClamAV sidecar (or a cloud scanning API). Uploads stay `PENDING_SCAN` until clean. Infected files are quarantined, and the asset is marked `REJECTED` and reported to the owner.
- This decides whether memories with unscanned assets can be locked. Recommendation: block locking until every asset is scanned.

### 2.3 Full ZIP export with original media · M
**Why.** Today's export is JSON only. It is the follow-up named in decisions §13 and a key trust feature for a permanence app.
- `POST /vaults/{id}/exports` starts an async job (outbox). The worker builds the ZIP (manifest JSON + original media + integrity hashes) in storage. The user gets a short-lived download URL and a notification (2.7).
- Export jobs are audited. Only one active export per user per vault. The ZIP expires after 7 days.

### 2.4 Vault-key rotation on member removal · L
**Why.** A removed member who kept the old vault key could decrypt content they obtain later (design §22). The gap is known and listed as "still open".
- Vault keys get versions (`vault_keys(vault_id, version, wrapped_key)`). Ciphertext records its key version.
- Removing a member (or a member leaving) creates a new key version. New content uses the new version.
- Old locked content stays under the old version, because re-encrypting would conflict with the immutability triggers and the integrity hashes. Document this trade-off in `decisions.md` before building.

### 2.5 Account deletion · M · ⚠ blocked
**Blocked on** a product/legal decision between policies A, B and C (design doc §41). Also needed for GDPR/CCPA.
- Recommended starting point is **Policy C**: personal vaults are purged through the existing deletion-request path (30-day grace). In shared vaults, the user's contributions stay, attributed to a "former member".
- Record the decision in `decisions.md` first. Then reuse the `DeletionRequest` + purge job machinery.

### 2.6 Password reset by email · M
- Email provider seam (SES/Postmark), single-use reset token (hashed, 30 min), revokes all refresh tokens.
- Content is unaffected because vault keys are wrapped by the KEK, not the password (decisions §2). This only works under server-side encryption. Sealed Vault (3.6) needs a different recovery model.

### 2.7 Push notifications · M
- Expo push tokens per device (`devices` table, design §23).
- Events through the outbox: memory sealed, time capsule revealed, "on this day", vault invitation, deletion request about to execute (reminders at 7 days and 1 day). That last one is a safety feature.
- Users can choose which notifications they get.

### 2.8 Video/audio transcoding · L
- HLS/MP4 renditions for playback, generated by the 2.1 worker. Same rules as thumbnails: originals untouched, renditions can be regenerated.

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

---

## Open decisions (resolve before the blocked work)

| Decision | Blocks | Owner |
|---|---|---|
| Account deletion policy (A/B/C, design §41) | 2.5 | Product + legal |
| Can memories lock before malware scan completes? | 2.2 | Product |
| Old content stays on old key version after member removal? | 2.4 | Engineering (record in decisions.md) |
| Recovery model for Sealed Vault | 3.6 | Product + security |
| ShedLock vs `SKIP LOCKED` for the outbox | 1.3 | Engineering |

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-17 | First version, based on `decisions.md` "Still open" and a review of the code. |
| 2026-10-17 | Done: 0.1, 0.2 (branch protection still manual), 1.1, and the export half of 1.6. |
